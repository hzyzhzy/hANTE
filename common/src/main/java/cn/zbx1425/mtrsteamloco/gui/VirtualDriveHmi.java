package cn.zbx1425.mtrsteamloco.gui;

import cn.zbx1425.mtrsteamloco.Main;
import cn.zbx1425.mtrsteamloco.game.TrainVirtualDrive;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.mojang.math.Axis;
import mtr.MTRClient;
import mtr.mappings.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;

import java.util.Random;

/**
 * 1.20.1 port of NTE's drive_hmi.png based cab display.
 *
 * <p>The original class was written against 1.20.4's BufferBuilder API (begin/buildOrThrow) and
 * cannot be compiled for 1.20.1. This version keeps the original atlas coordinates and layout,
 * but builds a 1.20.1 POSITION_TEX_COLOR quad per sprite.
 */
public final class VirtualDriveHmi {

    private static final ResourceLocation TEXTURE = new ResourceLocation(Main.MOD_ID, "textures/gui/drive_hmi.png");
    private static final int GAUGE_MAX_SPEED = 100;

    private static float delayedTrainSpeed;
    private static float delayedAtpYellowSpeed;
    private static float delayedAtpRedSpeed;
    private static float speedUpdateCooldown;
    private static final Random random = new Random();

    private VirtualDriveHmi() {
    }

    public static void render(GuiGraphics guiGraphics) {
        TrainVirtualDrive train = TrainVirtualDrive.activeTrain;
        if (train == null) {
            return;
        }

        Font font = Minecraft.getInstance().font;
        int realGaugeSize = Math.max(96, (guiGraphics.guiWidth() - 48) / 5);
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, guiGraphics.guiHeight(), 0.0F);
        guiGraphics.pose().scale(realGaugeSize / 96.0F, realGaugeSize / 96.0F, 1.0F);

        RenderSystem.setShaderTexture(0, TEXTURE);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.enableBlend();

        // Gauge face: shadow, bezel, white dial, inner dial.
        blit(guiGraphics, 25, -119, 24, 96, 0.125F, 0.5F, 0.125F, 0.5F, -2011028958);
        blit(guiGraphics, 24, -120, 24, 96, 0.125F, 0.5F, 0.125F, 0.5F, -1);
        blit(guiGraphics, 48, -120, 96, 96, 0.0F, 0.0F, 0.5F, 0.5F, -14540254);
        blit(guiGraphics, 48, -120, 96, 96, 0.5F, 0.0F, 0.5F, 0.5F, -1);

        speedUpdateCooldown -= MTRClient.getLastFrameDuration();
        if (speedUpdateCooldown <= 0.0F) {
            delayedTrainSpeed = train.getSpeed();
            delayedAtpYellowSpeed = train.atpYellowSpeed;
            delayedAtpRedSpeed = train.atpRedSpeed;
            speedUpdateCooldown = random.nextInt(3) + 2;
        }

        // Main needle.
        guiGraphics.pose().pushPose();
        guiGraphics.pose().rotateAround(
                Axis.ZP.rotationDegrees(-140.0F + Mth.clamp(Math.round(delayedTrainSpeed * 3.6F * 20.0F * 4.0F) / 4.0F, 0.0F, 100.0F) / 100.0F * 280.0F),
                96.0F, -72.0F, 0.0F);
        blit(guiGraphics, 84, -120, 24, 96, 0.0F, 0.5F, 0.125F, 0.5F, -1);
        guiGraphics.pose().popPose();

        if (!train.atpCutout) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().rotateAround(
                    Axis.ZP.rotationDegrees(-140.0F + Mth.clamp(Math.round(delayedAtpYellowSpeed * 3.6F * 20.0F * 3.0F) / 3.0F, 0.0F, 100.0F) / 100.0F * 280.0F),
                    96.0F, -72.0F, 0.0F);
            blit(guiGraphics, 84, -120, 24, 96, 0.375F, 0.5F, 0.125F, 0.5F, -1);
            guiGraphics.pose().popPose();

            guiGraphics.pose().pushPose();
            guiGraphics.pose().rotateAround(
                    Axis.ZP.rotationDegrees(-140.0F + Mth.clamp(Math.round(delayedAtpRedSpeed * 3.6F * 20.0F * 3.0F) / 3.0F, 0.0F, 100.0F) / 100.0F * 280.0F),
                    96.0F, -72.0F, 0.0F);
            blit(guiGraphics, 84, -120, 24, 96, 0.25F, 0.5F, 0.125F, 0.5F, -1);
            guiGraphics.pose().popPose();
        }

        // Platform / ATP / door icons.
        float platformDistance = (float) (train.nextPlatformRailProgress - train.getRailProgress());
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(147.0F, -120.0F, 0.0F);
        guiGraphics.pose().scale(0.375F, 0.375F, 1.0F);
        if (platformDistance < train.spacing * train.trainCars + 10) {
            blit(guiGraphics, 2, 2, 64, 64, 0.5F, 0.5F, 0.125F, 0.125F, -2011028958);
            if (Math.abs(platformDistance) < 1.0) {
                blit(guiGraphics, 0, 0, 64, 64, 0.5F, 0.5F, 0.125F, 0.125F, -1);
            } else {
                blit(guiGraphics, 0, 0, 64, 64, 0.625F, 0.5F, 0.125F, 0.125F, -1);
            }
        }
        if (train.atpEmergencyBrake) {
            blit(guiGraphics, 2, 66, 64, 64, 0.625F, 0.625F, 0.125F, 0.125F, -2011028958);
            blit(guiGraphics, 0, 64, 64, 64, 0.625F, 0.625F, 0.125F, 0.125F, -1);
        } else if (train.getDoorValue() > 0.0F) {
            blit(guiGraphics, 2, 66, 64, 64, 0.5F, 0.625F, 0.125F, 0.125F, -2011028958);
            blit(guiGraphics, 0, 64, 64, 64, 0.5F, 0.625F, 0.125F, 0.125F, -1);
        }
        guiGraphics.pose().popPose();

        // Speed digits.
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(96.0F, -72.0F, 0.0F);
        float speedTextScale = 6.575342F / 9.0F;
        guiGraphics.pose().scale(speedTextScale, speedTextScale, 1.0F);
        guiGraphics.pose().translate(0.0F, 0.5F, 0.0F);
        int speedKph = (int) Math.ceil(train.getSpeed() * 20.0F * 3.6F);
        if (speedKph >= 100) {
            guiGraphics.drawCenteredString(font, Integer.toString(speedKph), 0, -9 / 2, -1);
        } else if (speedKph >= 10) {
            guiGraphics.drawString(font, Integer.toString(speedKph % 10), 0, -9 / 2, -1);
            guiGraphics.drawString(font, Integer.toString(speedKph / 10), -font.width(Integer.toString(speedKph / 10)), -9 / 2, -1);
        } else {
            guiGraphics.drawString(font, Integer.toString(speedKph), 0, -9 / 2, -1);
        }
        guiGraphics.pose().popPose();

        // ATP target speed and target distance bar.
        if (train.atpTargetSpeed >= 0.0F && !train.atpCutout) {
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(36.0F, -103.5F, 0.0F);
            float targetSpeedTextScale = 4.875F / 9.0F;
            guiGraphics.pose().scale(targetSpeedTextScale, targetSpeedTextScale, 1.0F);
            int targetSpeedKph = Math.round(train.atpTargetSpeed * 20.0F * 3.6F);
            guiGraphics.drawString(font, Integer.toString(targetSpeedKph), -font.width(Integer.toString(targetSpeedKph)), 0, -1);
            guiGraphics.pose().popPose();

            double targetDistance = Mth.clamp(train.atpTargetDistance - train.getRailProgress(), 1.0, 750.0);
            float targetBarHeight = (float) (Math.log10(targetDistance) * 40.0 / 256.0 * 96.0);
            if (targetDistance > 1.0) {
                float x1 = 39.694286F;
                float x2 = x1 + 2.7428572F;
                float y2 = -51.0F;
                float y1 = y2 - targetBarHeight;
                int targetColor = -16744448;
                if (targetDistance < 150.0) {
                    if (targetSpeedKph == 0) {
                        targetColor = -65536;
                    } else if (targetSpeedKph < 60) {
                        targetColor = -23296;
                    }
                } else if (targetDistance < 300.0 && targetSpeedKph < 25) {
                    targetColor = -23296;
                }
                fill(guiGraphics, x1 + 1.0F, y1 + 1.0F, x2 + 1.0F, y2 + 1.0F, -2011028958);
                fill(guiGraphics, x1, y1, x2, y2, targetColor);
            }
        }

        // ATP overspeed warning block.
        if (!train.atpCutout) {
            float x1 = 24.0F;
            float x2 = x1 + 15.0F;
            float y1 = -123.75F;
            float y2 = y1 + 15.0F;
            int targetState = train.atpEmergencyBrake ? 2 : (train.getSpeed() > train.atpYellowSpeed + 0.0013888889F ? 1 : 0);
            if (targetState > 0) {
                fill(guiGraphics, x1 + 1.0F, y1 + 1.0F, x2 + 1.0F, y2 + 1.0F, -2011028958);
                fill(guiGraphics, x1, y1, x2, y2, targetState == 2 ? -65536 : -23296);
            }
        }

        // Reverser and notch.
        String reverserText = train.vdReverser == 0 ? "N" : (train.vdReverser < 0 ? "R" : "F");
        int reverserColor = train.vdReverser == 0 ? -16742332 : (train.vdReverser < 0 ? -23296 : -7829368);
        String notchText = train.vdNotch == 0
                ? "N"
                : (train.vdNotch < 0 ? "B" + Math.round(-train.getPercentNotch() * 100.0F) : "P" + Math.round(train.getPercentNotch() * 100.0F));
        int notchColor = train.vdNotch == 0 ? -7829368 : (train.vdNotch < 0 ? -23296 : -12417035);
        guiGraphics.drawString(font, Component.literal(reverserText), 24, -34, reverserColor);
        guiGraphics.drawString(font, Component.literal(notchText), 36, -34, notchColor);

        // Stop position and ATP EB warning.
        int lineHeight = 10;
        int y = -130 - lineHeight;
        int x = 20;
        if (Math.abs(platformDistance) < 10.0 && train.getSpeed() <= 0.0F) {
            String distanceText = platformDistance > -5.0 && platformDistance < 5.0
                    ? Math.round(platformDistance * 100.0) + " cm"
                    : Math.round(platformDistance) + " m";
            guiGraphics.drawString(font, Text.translatable("gui.mtrsteamloco.drive.stop_position", distanceText), x, y,
                    Math.abs(platformDistance) < 1.0 ? -14881403 : -23296);
            y -= lineHeight;
        }
        if (train.atpEmergencyBrake) {
            guiGraphics.drawString(font, Component.translatable("gui.mtrsteamloco.drive.atp_eb"), x, y, -65536);
        }

        RenderSystem.disableBlend();
        guiGraphics.pose().popPose();
    }

    private static void blit(GuiGraphics guiGraphics, int x, int y, int width, int height,
                             float minU, float minV, float deltaU, float deltaV, int color) {
        Matrix4f matrix = guiGraphics.pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        builder.vertex(matrix, x, y + height, 0.0F).uv(minU, minV + deltaV).color(color).endVertex();
        builder.vertex(matrix, x + width, y + height, 0.0F).uv(minU + deltaU, minV + deltaV).color(color).endVertex();
        builder.vertex(matrix, x + width, y, 0.0F).uv(minU + deltaU, minV).color(color).endVertex();
        builder.vertex(matrix, x, y, 0.0F).uv(minU, minV).color(color).endVertex();
        BufferUploader.drawWithShader(builder.end());
    }

    private static void fill(GuiGraphics guiGraphics, float minX, float minY, float maxX, float maxY, int color) {
        guiGraphics.fill((int) minX, (int) minY, (int) maxX, (int) maxY, color);
    }
}
