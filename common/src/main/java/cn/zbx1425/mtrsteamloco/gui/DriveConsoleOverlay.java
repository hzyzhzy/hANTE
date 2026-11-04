package cn.zbx1425.mtrsteamloco.gui;

import java.util.Locale;
import mtr.MTRClient;
import mtr.data.IGui;
import mtr.data.RailType;
import mtr.data.Route;
import mtr.data.Station;
import mtr.data.Train;
import mtr.data.TrainClient;
import cn.zbx1425.mtrsteamloco.game.TrainVirtualDrive;
import mtr.mappings.Text;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.player.LocalPlayer;

public class DriveConsoleOverlay implements IGui {
   private static int accelerationSign;
   private static float doorValue;
   private static float speed;
   private static String thisStation;
   private static String nextStation;
   private static String thisRoute;
   private static int coolDown;
   private static int trainCars;
   private static int cabCar;
   private static boolean[] carDoorLeftOpen = new boolean[0];
   private static boolean[] carDoorRightOpen = new boolean[0];
   private static double stoppingDistance;
   private static boolean onRoute;
   private static boolean hasUpcomingStop;
   private static int speedLimitKph;
   private static boolean automatic;
   private static float displayedSpeed;
   private static final int MARGIN = 6;
   private static final int HOTBAR_INSET = 22;
   private static final int CONSOLE_WIDTH = 238;
   private static final int CONSOLE_HEIGHT = 102;
   private static final float GAUGE_RADIUS = 42.0F;
   private static final int BUTTON_WIDTH = 44;
   private static final int BUTTON_HEIGHT = 9;
   private static final int PANEL_GAP = 6;
   private static final int DOOR_CELL = 16;
   private static final int DOOR_CELL_MIN = 8;
   private static final int DOOR_CELL_ABS_MIN = 5;
   private static final int DOOR_PANEL_PADDING = 12;
   private static final int DOOR_HEADER = 17;
   private static final int DOOR_ROW_STRIDE = 50;
   private static final int DOOR_ROW_CONTENT = 44;
   private static final int DOOR_MAX_ROWS = 4;
   private static final int COL_PANEL = -535817192;
   private static final int COL_BORDER = -13020309;
   private static final int COL_BORDER_DARK = -14997450;
   private static final int COL_TEXT = -1642506;
   private static final int COL_TEXT_DIM = -7100748;
   private static final int COL_TEXT_FAINT = -10258038;
   private static final int COL_BLUE = -12604939;
   private static final int COL_GREEN = -12722049;
   private static final int COL_YELLOW = -15043;
   private static final int COL_RED = -46267;
   private static final int COL_DIAL_BEZEL = -14404540;
   private static final int COL_DIAL_FACE = -16315375;
   private static final int COL_TICK = -3943714;
   private static final int COL_TICK_MINOR = -10522237;
   private static final int COL_TICK_RED = -5226440;
   private static final int COL_CAR_BAR = -14852184;
   private static final int COL_CAR_CAB = -11556368;
   private static final int COL_BUTTON_ON = -14782552;
   private static final int COL_BUTTON_OFF = -15392725;
   private static final String[] BUTTON_KEYS = new String[]{
      "gui.mtr.drive.button_load",
      "gui.mtr.drive.button_mode",
      "gui.mtr.drive.button_door",
      "gui.mtr.drive.button_signal",
      "gui.mtr.drive.button_other",
      "gui.mtr.drive.button_ato",
      "gui.mtr.drive.button_stop",
      "gui.mtr.drive.button_troubleshoot",
      "gui.mtr.drive.button_reset"
   };

   public static void render(GuiGraphics guiGraphics) {
      if (coolDown > 0) {
         coolDown--;
         Minecraft client = Minecraft.getInstance();
         LocalPlayer player = client.player;
         if (player != null) {
            Font font = client.font;
            float deltaTicks = MTRClient.getLastFrameDuration();
            displayedSpeed = displayedSpeed + (speed * 3.6F - displayedSpeed) * (float)(1.0 - Math.exp(-deltaTicks / 2.0));
            int screenWidth = guiGraphics.guiWidth();
            int bottom = guiGraphics.guiHeight() - 22 - 6;
            guiGraphics.pose().pushPose();
            renderConsole(guiGraphics, font, bottom);
            renderDoorPanel(guiGraphics, font, screenWidth, bottom);
            guiGraphics.pose().popPose();
         }
      }
   }

   private static void renderConsole(GuiGraphics g, Font font, int bottom) {
      int left = 6;
      int top = Math.max(6, bottom - 102);
      int right = 244;
      int buttonX = 195;
      drawPanel(g, 6, top, 244, top + 102);
      g.fill(192, top + 3, 193, top + 102 - 3, -14997450);
      float gaugeX = 56.0F;
      float gaugeY = top + 51.0F;
      renderGauge(g, font, 56.0F, gaugeY, 42.0F);
      int infoX = 104;
      int infoWidth = 81;
      String notchText;
      int notchColor;
      if (accelerationSign > 0) {
         notchText = "P" + accelerationSign;
         notchColor = -12604939;
      } else if (accelerationSign < 0) {
         notchText = "B" + -accelerationSign;
         notchColor = -46267;
      } else {
         notchText = "N";
         notchColor = -7100748;
      }

      drawScaledText(g, font, notchText, 104, top + 5, 2.2F, notchColor);
      g.drawString(font, t(automatic ? "gui.mtr.drive.mode_auto" : "gui.mtr.drive.mode_manual"), 140, top + 8, automatic ? -12604939 : -12722049, false);
      g.drawString(font, tf("gui.mtr.drive.speed_limit", speedLimitKph), 140, top + 19, -7100748, false);
      int y = top + 30;
      g.drawString(font, fit(font, t("gui.mtr.drive.this_station"), thisStation, 81), 104, y, -1642506, false);
      y += 11;
      g.drawString(font, fit(font, t("gui.mtr.drive.next_station"), nextStation, 81), 104, y, -15043, false);
      y += 11;
      String stopValue;
      int stopColor;
      if (onRoute && hasUpcomingStop) {
         double absDistance = Math.abs(stoppingDistance);
         if (absDistance < 0.5) {
            stopValue = t("gui.mtr.drive.stop_exact");
            stopColor = -12722049;
         } else if (absDistance < 5.0) {
            stopValue = String.format(Locale.ROOT, "%+.2f m", stoppingDistance);
            stopColor = -12722049;
         } else if (absDistance < 20.0) {
            stopValue = String.format(Locale.ROOT, "%+.1f m", stoppingDistance);
            stopColor = -15043;
         } else {
            stopValue = String.format(Locale.ROOT, "%.0f m", stoppingDistance);
            stopColor = -1642506;
         }
      } else if (onRoute) {
         stopValue = t("gui.mtr.drive.stop_running");
         stopColor = -10258038;
      } else {
         stopValue = "--";
         stopColor = -10258038;
      }

      g.drawString(font, fit(font, "", tf("gui.mtr.drive.stop_status", stopValue), 81), 104, y, stopColor, false);
      y += 11;
      g.drawString(font, fit(font, t("gui.mtr.drive.route"), thisRoute, 81), 104, y, -7100748, false);
      y += 11;
      int doorColor;
      String doorText;
      if (doorValue <= 0.0F) {
         doorText = t("gui.mtr.drive.door_closed");
         doorColor = -7100748;
      } else if (doorValue < 1.0F) {
         doorText = t("gui.mtr.drive.door_moving");
         doorColor = -15043;
      } else {
         doorText = t("gui.mtr.drive.door_open");
         doorColor = -12722049;
      }

      g.drawString(font, fit(font, "", tf("gui.mtr.drive.door_status", doorText), 81), 104, y, doorColor, false);
      y += 11;
      drawScaledText(g, font, fit(font, "", t("gui.mtr.drive.hint"), 108), 104, y, 0.75F, -10258038);
      drawButtons(g, font, 195, top + 7);
   }

   private static void renderGauge(GuiGraphics g, Font font, float cx, float cy, float radius) {
      fillCircle(g, cx, cy, radius + 3.0F, -14404540);
      fillCircle(g, cx, cy, radius, -16315375);
      int maxSpeed = gaugeMaxSpeed();
      int majorStep = gaugeStep(maxSpeed);
      int majorCount = Math.max(1, maxSpeed / majorStep);
      int minorCount = majorCount * 5;

      for (int i = 0; i <= minorCount; i++) {
         boolean major = i % 5 == 0;
         float fraction = (float)i / minorCount;
         float value = maxSpeed * fraction;
         float angle = (float)Math.toRadians(-140.0F + fraction * 280.0F);
         float halfWidth = major ? 1.2F : 0.7F;
         float outer = radius - 4.0F;
         float inner = major ? radius - 13.0F : radius - 9.0F;
         int color = value > speedLimitKph + 0.01F ? -5226440 : (major ? -3943714 : -10522237);
         g.pose().pushPose();
         rotateAbout(g.pose(), angle + (float) (Math.PI / 2), cx, cy);
         g.fill((int)(cx - halfWidth), (int)(cy - outer), (int)(cx + halfWidth), (int)(cy - inner), color);
         g.pose().popPose();
         if (major) {
            String label = Integer.toString(Math.round(value));
            float labelRadius = radius - 18.0F;
            float px = cx + (float)Math.cos(angle) * labelRadius;
            float py = cy + (float)Math.sin(angle) * labelRadius;
            g.drawString(font, label, (int)(px - font.width(label) / 2.0F), (int)(py - 4.0F), -7100748, false);
         }
      }

      g.fill((int)cx - 10, (int)cy + 8, (int)cx + 10, (int)cy + 27, -872415232);
      g.renderOutline((int)cx - 10, (int)cy + 8, 20, 19, -13876649);
      String speedText = Integer.toString(Math.round(Math.max(displayedSpeed, 0.0F)));
      drawScaledCenteredText(g, font, speedText, cx, (int)cy + 12, speedText.length() >= 3 ? 1.0F : 1.6F, -1642506);
      drawScaledCenteredText(g, font, t("gui.mtr.drive.unit_kmh"), cx, (int)cy + 28, 0.6F, -10258038);
      float needleFraction = Math.max(0.0F, Math.min(1.0F, displayedSpeed / maxSpeed));
      float needleAngle = (float)Math.toRadians(-140.0F + needleFraction * 280.0F);
      g.pose().pushPose();
      rotateAbout(g.pose(), needleAngle + (float) (Math.PI / 2), cx, cy);
      g.fill((int)cx - 1, (int)(cy - radius + 12.0F), (int)cx + 1, (int)cy, -46267);
      g.pose().popPose();
      fillCircle(g, cx, cy, 3.5F, -2497300);
      fillCircle(g, cx, cy, 1.5F, -13876649);
      float limitFraction = Math.max(0.0F, Math.min(1.0F, (float)speedLimitKph / maxSpeed));
      float limitAngle = (float)Math.toRadians(-140.0F + limitFraction * 280.0F);
      g.pose().pushPose();
      rotateAbout(g.pose(), limitAngle + (float) (Math.PI / 2), cx, cy);
      g.fill((int)cx - 2, (int)(cy - radius + 1.0F), (int)cx + 2, (int)(cy - radius + 8.0F), -46267);
      g.pose().popPose();
   }

   private static void renderDoorPanel(GuiGraphics g, Font font, int screenWidth, int bottom) {
      int cars = trainCars;
      if (cars > 0 && carDoorLeftOpen.length >= cars) {
         int openCars = 0;

         for (int i = 0; i < cars; i++) {
            if (carDoorLeftOpen[i] || carDoorRightOpen[i]) {
               openCars++;
            }
         }

         String title = t("gui.mtr.drive.door_panel_title");
         String summary = tf("gui.mtr.drive.door_open_count", openCars, cars);
         int headerWidth = font.width(title) + 8 + font.width(summary) + 12;
         int available = screenWidth - 6 - 238 - 6 - 6;
         int maxRows = Math.max(1, Math.min(4, (bottom - 6 - 17 - 44 - 5) / 50 + 1));
         int cell = 16;
         int rows = 1;

         int perRow;
         while (true) {
            perRow = (cars + rows - 1) / rows;
            int panelWidth = Math.max(12 + perRow * cell, headerWidth);
            if (panelWidth <= available) {
               break;
            }

            if (cell > 8) {
               cell--;
            } else {
               if (rows >= maxRows) {
                  break;
               }

               rows++;
               cell = 16;
            }
         }

         while (cell > 5 && Math.max(12 + perRow * cell, headerWidth) > available) {
            cell--;
         }

         int var37 = Math.max(12 + perRow * cell, headerWidth);
         float textScale = cell >= 14 ? 1.0F : (cell >= 11 ? 0.85F : 0.7F);
         int panelHeight = 17 + (rows - 1) * 50 + 44 + 5;
         int left = Math.max(6, screenWidth - var37 - 6);
         int top = bottom - panelHeight;
         int right = left + var37;
         drawPanel(g, left, top, right, top + panelHeight);
         g.drawString(font, title, left + 6, top + 5, -1642506, false);
         g.drawString(font, summary, right - 6 - font.width(summary), top + 5, openCars > 0 ? -12722049 : -10258038, false);

         for (int row = 0; row < rows; row++) {
            int firstCar = row * perRow;
            int carsInRow = Math.min(perRow, cars - firstCar);
            if (carsInRow <= 0) {
               break;
            }

            int rowTop = top + 17 + row * 50;
            int rowBottom = rowTop + 16;
            int rowLeft = left + (var37 - carsInRow * cell) / 2;
            g.fill(rowLeft, rowTop, rowLeft + carsInRow * cell, rowBottom, -14852184);

            for (int ix = firstCar; ix < firstCar + carsInRow; ix++) {
               int column = ix - firstCar;
               int x = rowLeft + column * cell;
               float centerX = x + (cell - 1) / 2.0F;
               boolean leftOpen = carDoorLeftOpen[ix];
               boolean rightOpen = carDoorRightOpen[ix];
               boolean open = leftOpen || rightOpen;
               boolean isCab = ix == cabCar;
               String carLabel = Integer.toString(ix + 1);
               if (isCab) {
                  g.fill(x, rowTop, x + cell - 1, rowBottom, -11556368);
               }

               if (column > 0) {
                  g.fill(x - 1, rowTop, x, rowBottom, 1429229680);
               }

               drawScaledCenteredText(
                  g, font, carLabel, centerX, rowTop + 5, Math.min(textScale, (cell - 1) / (6.0F * carLabel.length())), isCab ? -1 : -1379590
               );
               drawScaledCenteredText(
                  g, font, open ? "1" : "0", centerX, rowTop + 20, Math.min(textScale * 1.5F, (cell - 1) / 6.0F), open ? -12722049 : -10258038
               );
               String direction = leftOpen && rightOpen
                  ? t("gui.mtr.drive.door_side_both")
                  : (leftOpen ? t("gui.mtr.drive.door_side_left") : (rightOpen ? t("gui.mtr.drive.door_side_right") : t("gui.mtr.drive.door_side_none")));
               drawScaledCenteredText(g, font, direction, centerX, rowTop + 34, Math.min(textScale * 0.8F, (cell - 1) / 18.0F), open ? -12722049 : -10258038);
            }
         }
      }
   }

   private static void drawButtons(GuiGraphics g, Font font, int x, int top) {
      boolean[] active = new boolean[]{accelerationSign > 0, automatic, doorValue > 0.0F, onRoute, false, automatic, speed <= 0.01F, false, false};

      for (int i = 0; i < BUTTON_KEYS.length; i++) {
         int buttonTop = top + i * 10;
         boolean on = active[i];
         g.fill(x, buttonTop, x + 44, buttonTop + 9, on ? -14782552 : -15392725);
         g.renderOutline(x, buttonTop, 44, 9, on ? -12604939 : -14997450);
         drawScaledText(g, font, t(BUTTON_KEYS[i]), x + 5, buttonTop + 1, 0.8F, on ? -1 : -10258038);
      }
   }

   private static void drawPanel(GuiGraphics g, int left, int top, int right, int bottom) {
      g.fill(left, top, right, bottom, -535817192);
      g.renderOutline(left, top, right - left, bottom - top, -13020309);
      g.fill(left + 1, top + 1, right - 1, top + 3, -14997450);
   }

   private static void fillCircle(GuiGraphics g, float cx, float cy, float radius, int color) {
      int r = (int)Math.ceil(radius);

      for (int dy = -r; dy <= r; dy++) {
         float half = (float)Math.sqrt(Math.max(0.0F, radius * radius - dy * dy));
         if (!(half < 0.5F)) {
            g.fill((int)(cx - half), (int)(cy + dy), (int)Math.ceil(cx + half), (int)(cy + dy + 1.0F), color);
         }
      }
   }

   private static int gaugeMaxSpeed() {
      float target = Math.max(Math.max((float)speedLimitKph, speed * 3.6F), 20.0F) * 1.1F;

      for (int candidate : new int[]{40, 60, 80, 100, 120, 160, 200, 250, 300, 350, 400, 450}) {
         if (target <= candidate) {
            return candidate;
         }
      }

      return (int)(Math.ceil(target / 50.0F) * 50.0);
   }

   private static int gaugeStep(int maxSpeed) {
      int[] steps = new int[]{5, 10, 20, 25, 50, 100, 150};

      for (int maxDivisions : new int[]{6, 8}) {
         for (int step : steps) {
            if (maxSpeed % step == 0 && maxSpeed / step <= maxDivisions) {
               return step;
            }
         }
      }

      return Math.max(1, maxSpeed / 8);
   }

   private static void rotateAbout(PoseStack pose, float angle, float cx, float cy) {
      pose.translate(cx, cy, 0.0F);
      pose.mulPose(Axis.ZP.rotation(angle));
      pose.translate(-cx, -cy, 0.0F);
   }

   private static void drawScaledText(GuiGraphics g, Font font, String text, int x, int y, float scale, int color) {
      g.pose().pushPose();
      g.pose().translate((float) x, (float) y, 0.0F);
      g.pose().scale(scale, scale, 1.0F);
      g.drawString(font, text, 0, 0, color, false);
      g.pose().popPose();
   }

   private static void drawScaledCenteredText(GuiGraphics g, Font font, String text, float centerX, int y, float scale, int color) {
      g.pose().pushPose();
      g.pose().translate(centerX, (float) y, 0.0F);
      g.pose().scale(scale, scale, 1.0F);
      g.drawCenteredString(font, text, 0, 0, color);
      g.pose().popPose();
   }

   private static String t(String key) {
      return Text.translatable(key).getString();
   }

   private static String tf(String key, Object... args) {
      return Text.translatable(key, args).getString();
   }

   private static String fit(Font font, String prefix, String value, int maxWidth) {
      String text = value == null ? "--" : value;
      if (font.width(prefix + text) <= maxWidth) {
         return prefix + text;
      } else {
         String shortened = text;

         while (shortened.length() > 1 && font.width(prefix + shortened + "...") > maxWidth) {
            shortened = shortened.substring(0, shortened.length() - 1);
         }

         return prefix + shortened + "...";
      }
   }

   public static void setData(int accelerationSign, TrainVirtualDrive trainClient) {
      DriveConsoleOverlay.accelerationSign = accelerationSign;
      doorValue = trainClient.getDoorValue();
      coolDown = 2;
      speed = trainClient.getSpeed() * 20.0F;
      Route thisRoute = trainClient.getThisRoute();
      Route nextRoute = trainClient.getNextRoute();
      Station thisStation = trainClient.getThisStation();
      Station nextStation = trainClient.getNextStation();
      DriveConsoleOverlay.thisStation = thisStation == null ? null : IGui.formatStationName(thisStation.name);
      DriveConsoleOverlay.nextStation = nextStation == null
         ? (nextRoute == null ? null : IGui.formatStationName(nextRoute.name))
         : IGui.formatStationName(nextStation.name);
      DriveConsoleOverlay.thisRoute = thisRoute == null ? null : IGui.formatStationName(thisRoute.name);
      trainCars = trainClient.trainCars;
      cabCar = trainClient.isReversed() ? trainCars - 1 : 0;
      if (carDoorLeftOpen.length != trainCars) {
         carDoorLeftOpen = new boolean[Math.max(trainCars, 0)];
         carDoorRightOpen = new boolean[Math.max(trainCars, 0)];
      }

      boolean doorsOpen = trainClient.getDoorValue() > 0.0F;
      for (int i = 0; i < trainCars && i < carDoorLeftOpen.length; i++) {
         carDoorLeftOpen[i] = doorsOpen;
         carDoorRightOpen[i] = doorsOpen;
      }

      onRoute = trainClient.isOnRoute();
      stoppingDistance = trainClient.getDistanceToNextStopForHmi();
      hasUpcomingStop = trainClient.hasUpcomingStopForHmi();
      RailType speedLimit = Train.convertMaxManualSpeed(trainClient.maxManualSpeed);
      speedLimitKph = speedLimit == null ? RailType.IRON.speedLimit : speedLimit.speedLimit;
      automatic = !trainClient.isCurrentlyManual();
   }
}
