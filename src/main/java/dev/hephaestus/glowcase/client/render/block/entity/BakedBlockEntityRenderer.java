package dev.hephaestus.glowcase.client.render.block.entity;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.hephaestus.glowcase.mixin.client.render.BufferBuilderAccessor;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Matrix4f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public abstract class BakedBlockEntityRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {
	protected final BlockEntityRendererFactory.Context context;

	protected BakedBlockEntityRenderer(BlockEntityRendererFactory.Context context) {
		this.context = context;
	}

	/**
	 * Handles invalidation and passing of rendered vertices to the baking system.
	 * Override renderBaked and renderUnbaked instead of this method.
	 */
	@Override
	public final void render(T entity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
		renderUnbaked(entity, tickDelta, matrices, vertexConsumers, light, overlay);
		VertexBufferManager.INSTANCE.activateRegion(entity.getPos());
	}

	/**
	 * Render vertices to be baked into the render region. This method will be called every time the render region is rebuilt - so
	 * you should only render vertices that don't move here. You can call invalidateSelf or VertexBufferManager.invalidate to
	 * cause the render region to be rebuilt, but do not call this too frequently as it will affect performance.
	 *
	 * You must use the provided VertexConsumerProvider and MatrixStack to render your vertices - any use of Tessellator
	 * or RenderSystem here will not work. If you need custom rendering settings, you can use a custom RenderLayer.
	 */
	public abstract void renderBaked(T entity, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay);

	/**
	 * Render vertices immediately. This works exactly the same way as a normal BER render method, and can be used for dynamic
	 * rendering that changes every frame. In this method you can also check for render invalidation and call invalidateSelf
	 * as appropriate.
	 */
	public abstract void renderUnbaked(T entity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay);

	/**
	 * Causes the render region containing this BlockEntity to be rebuilt -
	 * do not call this too frequently as it will affect performance.
	 * An invalidation will not immediately cause the next frame to contain an updated view (and call to renderBaked)
	 * as all render region rebuilds must call every BER that is to be rendered, otherwise they will be missing from the
	 * vertex buffer.
	 */
	public void invalidate(BlockPos pos) {
		VertexBufferManager.INSTANCE.invalidateRegion(pos);
	}

	private static record RenderRegionPos(int x, int z, @Nullable BlockPos origin) {
		public RenderRegionPos(int x, int z) {
			this(x, z, new BlockPos(x << VertexBufferManager.REGION_SHIFT, 0, z << VertexBufferManager.REGION_SHIFT));
		}

		public RenderRegionPos(BlockPos pos) {
			this(pos.getX() >> VertexBufferManager.REGION_SHIFT, pos.getZ() >> VertexBufferManager.REGION_SHIFT);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			RenderRegionPos that = (RenderRegionPos) o;
			return x == that.x &&
				z == that.z;
		}

		@Override
		public int hashCode() {
			return Objects.hash(x, z);
		}
	}

	public static class VertexBufferManager {
		public static final VertexBufferManager INSTANCE = new VertexBufferManager();

		// 2x2 chunks size for regions
		public static final int REGION_FROMCHUNK_SHIFT = 1;
		public static final int REGION_SHIFT = 4 + REGION_FROMCHUNK_SHIFT;
		public static final int MAX_XZ_IN_REG = (16 << REGION_FROMCHUNK_SHIFT) - 1;
		public static final int VIEW_RADIUS = 3;

		private final Map<RenderRegionPos, RegionBuffer> regions = new HashMap<>();

		private final Set<RenderRegionPos> invalidRegions = Sets.newHashSet();
		private final Map<RenderRegionPos, RegionBufferBuilder> builders = new Object2ObjectArrayMap<>();

		private ClientWorld currentWorld = null;

		public VertexBufferManager() {
			// Register callback, to rebuild all when fonts/render chunks are changed
			InvalidateRenderStateCallback.EVENT.register(this::reset);
		}

		private static class RegionBuffer {
			private final Map<RenderLayer, VertexBuffer> layerBuffers = new Object2ObjectArrayMap<>();

			public void render(RenderLayer l, MatrixStack matrices, Matrix4f projectionMatrix) {
				VertexBuffer buf = layerBuffers.get(l);
				buf.bind();
				buf.draw(matrices.peek().getPositionMatrix(), projectionMatrix, RenderSystem.getShader());
			}

			public void upload(RenderLayer l, BufferBuilder newBuf) {
				VertexBuffer buf = layerBuffers.computeIfAbsent(l, renderLayer -> new VertexBuffer());
				// TODO: translucency sorting?
				buf.bind();
				buf.upload(newBuf.end());
			}

			public void deallocate() {
				for (VertexBuffer buf : layerBuffers.values()) {
					buf.close();
				}
			}

			public Set<RenderLayer> getAllLayers() {
				return layerBuffers.keySet();
			}
		}

		private static class RegionBufferBuilder implements VertexConsumerProvider, Iterable<Map.Entry<RenderLayer, BufferBuilder>> {
			private static final BlockEntityRenderDispatcher renderDispatcher = MinecraftClient.getInstance().getBlockEntityRenderDispatcher();

			private final Map<RenderLayer, BufferBuilder> layerBuffers = new Object2ObjectArrayMap<>();

			private <E extends BlockEntity> BakedBlockEntityRenderer<E> getRenderer(E be) {
				if (renderDispatcher.get(be) instanceof BakedBlockEntityRenderer<E> bakedBer) return bakedBer;
				return null;
			}

			private <E extends BlockEntity> boolean shouldBake(E be) {
				return getRenderer(be) != null;
			}

			public void build(List<BlockEntity> blockEntities) {
				MatrixStack bakeMatrices = new MatrixStack();

				for (BlockEntity be : blockEntities) {
					if (!shouldBake(be)) continue;

					BlockPos pos = be.getPos();
					World world = be.getWorld();

					int light = world != null ? WorldRenderer.getLightmapCoordinates(world, pos) : LightmapTextureManager.MAX_LIGHT_COORDINATE;
					
					bakeMatrices.push();
					bakeMatrices.translate(pos.getX() & VertexBufferManager.MAX_XZ_IN_REG, pos.getY(), pos.getZ() & VertexBufferManager.MAX_XZ_IN_REG);
					getRenderer(be).renderBaked(be, bakeMatrices, this, light, OverlayTexture.DEFAULT_UV);
					bakeMatrices.pop();
				}
			}

			public void reset() {
				for (BufferBuilder buf : layerBuffers.values()) {
					((BufferBuilderAccessor)buf).invokeResetBuilding();
				}
			}

			@Override
			public VertexConsumer getBuffer(RenderLayer layer) {
				return layerBuffers.compute(layer, (l, buf) -> {
					if (buf == null) buf = new BufferBuilder(l.getExpectedBufferSize());
					if (!buf.isBuilding()) buf.begin(l.getDrawMode(), l.getVertexFormat());
					return buf;
				});
			}

			public @NotNull Iterator<Map.Entry<RenderLayer, BufferBuilder>> iterator() {
				return layerBuffers.entrySet().iterator();
			}
		}

		public void invalidateRegion(BlockPos pos) {
			// Mark a region as invalid. Before the current set of rebuilding regions (invalid regions from the last frame) have been
			// built, a RegionBuilder will be created for this region and passed to all BERs to render to
			invalidRegions.add(new RenderRegionPos(pos));
		}

		// TODO: move chunk baking off-thread?

		private boolean isVisiblePos(RenderRegionPos rrp, Vec3d cameraPos) {
			return Math.abs(rrp.x - ((int)cameraPos.getX() >> REGION_SHIFT)) <= VIEW_RADIUS && Math.abs(rrp.z - ((int)cameraPos.getZ() >> REGION_SHIFT)) <= VIEW_RADIUS;
		}

		public void render(MatrixStack matrices, Matrix4f projectionMatrix, Camera camera) {
			Vec3d cameraPos = camera.getPos();

			// Make builders for invalidated regions
			for (RenderRegionPos rrp : invalidRegions) {
				builders.compute(rrp, (k, v) -> {
					if (v != null) v.reset();
					else v = new RegionBufferBuilder();
					return v;
				});
			}
			invalidRegions.clear();

			// Iterate over all RegionBuilders, render and upload to RegionBuffers
			List<BlockEntity> blockEntities = new ArrayList<>();
			for (Map.Entry<RenderRegionPos, RegionBufferBuilder> entryBuilder : builders.entrySet()) {
				RenderRegionPos rrp = entryBuilder.getKey();
				if (isVisiblePos(rrp, cameraPos)) {
					// For the current region, rebuild each render layer using the buffer builders
					// Find all block entities in this region
					if (currentWorld == null) {
						break;
					}

					for (int chunkX = rrp.x << REGION_FROMCHUNK_SHIFT; chunkX < (rrp.x + 1) << REGION_FROMCHUNK_SHIFT; chunkX++) {
						for (int chunkZ = rrp.z << REGION_FROMCHUNK_SHIFT; chunkZ < (rrp.z + 1) << REGION_FROMCHUNK_SHIFT; chunkZ++) {
							blockEntities.addAll(currentWorld.getChunk(chunkX, chunkZ).getBlockEntities().values());
						}
					}

					entryBuilder.getValue().build(blockEntities);
					blockEntities.clear();

					RegionBuffer buf = regions.computeIfAbsent(entryBuilder.getKey(), k -> new RegionBuffer());
					for (Map.Entry<RenderLayer, BufferBuilder> layerBuilder : entryBuilder.getValue()) {
						buf.upload(layerBuilder.getKey(), layerBuilder.getValue());
					}
				}
			}

			// Iterate over all RegionBuffers, render visible and remove non-visible RegionBuffers
			Iterator<Map.Entry<RenderRegionPos, RegionBuffer>> iterBuffers = regions.entrySet().iterator();
			while (iterBuffers.hasNext()) {
				Map.Entry<RenderRegionPos, RegionBuffer> entry = iterBuffers.next();
				RenderRegionPos rrp = entry.getKey();
				RegionBuffer regionBuffer = entry.getValue();
				if (isVisiblePos(entry.getKey(), cameraPos)) {
					// Iterate over used render layers in the region, render them
					matrices.push();
					matrices.translate(rrp.origin.getX() - cameraPos.x, rrp.origin.getY() - cameraPos.y, rrp.origin.getZ() - cameraPos.z);
					for (RenderLayer layer : regionBuffer.getAllLayers()) {
						layer.startDrawing();
						regionBuffer.render(layer, matrices, projectionMatrix);
						layer.endDrawing();
					}
					matrices.pop();
				} else {
					regionBuffer.deallocate();
					iterBuffers.remove();
				}
			}
		}

		public void activateRegion(BlockPos pos) {
			RenderRegionPos rrp = new RenderRegionPos(pos);
			if (!regions.containsKey(rrp)) {
				builders.put(rrp, new RegionBufferBuilder());
			}
		}

		private void reset() {
			// Reset everything
			for (RegionBuffer buf : regions.values()) {
				buf.deallocate();
			}
			regions.clear();
			invalidRegions.clear();
			builders.clear();
		}

		public void setWorld(ClientWorld world) {
			reset();
			currentWorld = world;
		}
	}
}
