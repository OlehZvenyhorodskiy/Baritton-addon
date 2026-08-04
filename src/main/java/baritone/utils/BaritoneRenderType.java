/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

public class BaritoneRenderType extends RenderType {
    private final RenderPipeline renderPipeline;

    public BaritoneRenderType(String name, int bufferSize, boolean affectsCrumbling, boolean sortOnUpload, RenderPipeline renderPipeline) {
        super(name, bufferSize, affectsCrumbling, sortOnUpload, () -> {}, () -> {});
        this.renderPipeline = renderPipeline;
    }

    public static BaritoneRenderType create(String name, int bufferSize, RenderPipeline renderPipeline) {
        return new BaritoneRenderType(name, bufferSize, false, false, renderPipeline);
    }

    @Override
    public VertexFormat format() {
        return this.renderPipeline.getVertexFormat();
    }

    @Override
    public VertexFormat.Mode mode() {
        return this.renderPipeline.getVertexFormatMode();
    }

    @Override
    public void draw(final MeshData meshData) {
        this.setupRenderState();
        try {
            GpuBuffer vertexBuffer = this.renderPipeline.getVertexFormat().uploadImmediateVertexBuffer(meshData.vertexBuffer());
            GpuBuffer indexBuffer;
            VertexFormat.IndexType indexType;
            if (meshData.indexBuffer() == null) {
                RenderSystem.AutoStorageIndexBuffer autoStorageIndexBuffer = RenderSystem.getSequentialBuffer(meshData.drawState().mode());
                indexBuffer = autoStorageIndexBuffer.getBuffer(meshData.drawState().indexCount());
                indexType = autoStorageIndexBuffer.type();
            } else {
                indexBuffer = this.renderPipeline.getVertexFormat().uploadImmediateIndexBuffer(meshData.indexBuffer());
                indexType = meshData.drawState().indexType();
            }

            RenderTarget renderTarget = RenderStateShard.MAIN_TARGET.getRenderTarget();
            GpuTextureView colorTextureTarget = RenderSystem.outputColorTextureOverride != null
                ? RenderSystem.outputColorTextureOverride
                : renderTarget.getColorTextureView();
            GpuTextureView depthTextureTarget = renderTarget.useDepth
                ? (RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : renderTarget.getDepthTextureView())
                : null;

            GpuBufferSlice dynamicTransformsUbo = RenderSystem.getDynamicUniforms()
                .writeTransform(
                    RenderSystem.getModelViewMatrix(),
                    new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
                    RenderSystem.getModelOffset(),
                    RenderSystem.getTextureMatrix(),
                    RenderSystem.getShaderLineWidth()
                );

            try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> "Immediate draw for " + this.getName(), colorTextureTarget, OptionalInt.empty(), depthTextureTarget, OptionalDouble.empty())) {
                renderPass.setPipeline(this.renderPipeline);
                ScissorState scissorState = RenderSystem.getScissorStateForRenderTypeDraws();
                if (scissorState.enabled()) {
                    renderPass.enableScissor(scissorState.x(), scissorState.y(), scissorState.width(), scissorState.height());
                }

                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("DynamicTransforms", dynamicTransformsUbo);
                renderPass.setVertexBuffer(0, vertexBuffer);

                for (int i = 0; i < 12; i++) {
                    GpuTextureView texture = RenderSystem.getShaderTexture(i);
                    if (texture != null) {
                        renderPass.bindSampler("Sampler" + i, texture);
                    }
                }

                renderPass.setIndexBuffer(indexBuffer, indexType);
                renderPass.drawIndexed(0, 0, meshData.drawState().indexCount(), 1);
            }
        } catch (Throwable e) {
            try {
                meshData.close();
            } catch (Throwable e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }

        meshData.close();

        this.clearRenderState();
    }
}