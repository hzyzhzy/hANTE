package cn.zbx1425.mtrsteamloco.game;

import cn.zbx1425.mtrsteamloco.network.PacketVirtualDrive;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Random;
import mtr.client.ClientData;
import mtr.data.TrainClient;
import mtr.data.TrainClient.AnnouncementCallback;
import mtr.data.TrainClient.SpeedCallback;
import mtr.mappings.Text;
import mtr.path.PathData;
import mtr.data.Route;
import mtr.data.Station;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;
import net.minecraft.Util;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public class TrainVirtualDrive extends TrainClient {
   public int vdNotch = 0;
   public int vdReverser = 1;
   public float vdMaxSpeed;
   public int nextPlatformIndex;
   public double nextPlatformRailProgress;
   public float atpYellowSpeed;
   public float atpRedSpeed;
   public float atpTargetSpeed;
   public double atpTargetDistance;
   public boolean atpEmergencyBrake;
   public boolean atpCutout = false;
   private int doorOpenedAtPlatformIndex = -1;
   public LongArrayList railAheadLookup = new LongArrayList();
   private final DelayedValue actualNotch = new DelayedValue(0.5);
   public int powerNotches = 5;
   public int brakeNotches = 7;
   public float yellowSpeedBrakeRatio = 0.7F;
   public float redSpeedBrakeRatio = 0.9F;
   public float motorPowerPerCar = 240000.0F;
   public float weightPerCar = 27000.0F;
   public double PROG_TOLERANCE = 5.0;
   public static TrainVirtualDrive activeTrain;
   private TrainClient sourceTrain;

   public TrainVirtualDrive(TrainClient trainClient) {
      super((FriendlyByteBuf)Util.make(() -> {
         FriendlyByteBuf packet = new FriendlyByteBuf(Unpooled.buffer());
         trainClient.writePacket(packet);
         int prevWriterIndex = packet.writerIndex();
         packet.writerIndex(0);
         packet.writeLong(new Random().nextLong());
         packet.writerIndex(prevWriterIndex);
         packet.resetReaderIndex();
         return packet;
      }));

      this.sourceTrain = trainClient;
      for (PathData path : this.path) {
         this.vdMaxSpeed = Math.max(this.vdMaxSpeed, path.rail.railType.maxBlocksPerTick);
      }

      this.vdMaxSpeed += 0.2777778F;
   }

   @Override
   public void simulateTrain(
      Level world,
      float ticksElapsed,
      SpeedCallback speedCallback,
      AnnouncementCallback announcementCallback,
      AnnouncementCallback lightRailAnnouncementCallback
   ) {
      this.isCurrentlyManual = true;
      this.manualNotch = 114514;
      this.nextStoppingIndex = this.path.size() - 1;
      super.simulateTrain(world, ticksElapsed, speedCallback, announcementCallback, lightRailAnnouncementCallback);
      if (this.isOnRoute && !(ClientData.getShiftHoldingTicks() >= 30.0F)) {
         if (this.isRepeat()) {
            int headIndex = this.getIndex(this.railProgress + this.PROG_TOLERANCE, false);
            if (headIndex >= this.repeatIndex2 && this.distances.size() > this.repeatIndex1) {
               if (this.path.get(this.repeatIndex2).isOppositeRail(this.path.get(this.repeatIndex1))) {
                  if (this.speed <= 0.0F) {
                     this.railProgress = this.distances.get(this.repeatIndex1 - 1) + this.trainCars * this.spacing;
                     this.nextPlatformIndex = 0;
                     this.doorOpenedAtPlatformIndex = -1;
                     this.reversed = !this.reversed;
                     Minecraft.getInstance().player.sendSystemMessage(Text.translatable("gui.mtrsteamloco.drive.change_end"));
                  }
               } else {
                  this.railProgress = this.distances.get(this.repeatIndex1) + (this.railProgress - this.distances.get(this.repeatIndex2));
                  this.nextPlatformIndex = 0;
                  this.doorOpenedAtPlatformIndex = -1;
               }
            }
         }

         if (this.speed <= 0.0F) {
            int tailIndex = this.getIndex(this.railProgress - this.spacing * this.trainCars + this.PROG_TOLERANCE, false);
            if (this.path.size() > tailIndex + 1
               && Math.abs(this.railProgress - this.distances.get(tailIndex)) < this.PROG_TOLERANCE
               && this.path.get(tailIndex).isOppositeRail(this.path.get(tailIndex + 1))) {
               this.railProgress = this.distances.get(tailIndex) + this.trainCars * this.spacing + 0.1;
               this.reversed = !this.reversed;
               Minecraft.getInstance().player.sendSystemMessage(Text.translatable("gui.mtrsteamloco.drive.change_end"));
            }
         }

         float accelDueToFriction = -1.3888889E-4F;
         // YMTR 3.6.3 does not expose keyPointsPositions/getBogiePosition; omit pitch acceleration.
         float sinPitch = 0F;
         float accelDueToPitch = sinPitch * 9.8F / 400.0F;
         float passiveAccel = accelDueToFriction + accelDueToPitch;
         float commandNotch;
         if (this.atpEmergencyBrake) {
            commandNotch = -2.0F;
            if (this.speed <= 0.0F && this.vdNotch < 0) {
               this.atpEmergencyBrake = false;
            }
         } else {
            commandNotch = this.getPercentNotch();
         }

         float actualNotch = this.actualNotch.setAndGet(commandNotch, ticksElapsed);
         if (actualNotch < -1.0F) {
            this.speed = Mth.clamp(
               this.speed - 1.1F * (this.accelerationConstant / this.yellowSpeedBrakeRatio) * ticksElapsed + passiveAccel * ticksElapsed, 0.0F, this.vdMaxSpeed
            );
         } else if (actualNotch < 0.0F) {
            this.speed = Mth.clamp(
               this.speed + actualNotch * (this.accelerationConstant / this.yellowSpeedBrakeRatio) * ticksElapsed + passiveAccel * ticksElapsed,
               0.0F,
               this.vdMaxSpeed
            );
         } else if (actualNotch > 0.0F) {
            float maxPowerAccel = this.motorPowerPerCar / (this.weightPerCar * (this.speed * 20.0F));
            float motorOutputAccel;
            if (this.accelerationConstant * 400.0F > maxPowerAccel) {
               motorOutputAccel = maxPowerAccel * actualNotch;
            } else {
               motorOutputAccel = this.accelerationConstant * 400.0F * actualNotch;
            }

            this.speed = Mth.clamp(this.speed + motorOutputAccel / 400.0F * ticksElapsed + passiveAccel * ticksElapsed, 0.0F, this.vdMaxSpeed);
         } else {
            this.speed = Mth.clamp(this.speed + passiveAccel * ticksElapsed, 0.0F, this.vdMaxSpeed);
         }

         if (this.doorValue > 0.0F || this.doorTarget) {
            this.speed = 0.0F;
         }

         if (this.distances.get(this.nextPlatformIndex) < this.railProgress - 10.0) {
            for (int i = this.nextPlatformIndex; i < this.path.size(); i++) {
               if (this.path.get(i).dwellTime > 0 && this.distances.get(i) >= this.railProgress - 10.0) {
                  this.nextPlatformIndex = i;
                  this.nextPlatformRailProgress = this.distances.get(i);
                  break;
               }

               if (i == this.path.size() - 1) {
                  this.nextPlatformIndex = i;
                  this.nextPlatformRailProgress = this.distances.get(i);
               }
            }
         }

         this.railAheadLookup.clear();
         this.atpYellowSpeed = this.vdMaxSpeed;
         this.atpRedSpeed = this.vdMaxSpeed;
         this.atpTargetSpeed = this.vdMaxSpeed;
         float targetPatternSpeed = this.vdMaxSpeed;
         float effectiveLimit = this.vdMaxSpeed;
         this.atpTargetDistance = (Double)this.distances.get(this.distances.size() - 1);
         double lookAheadDistance = Math.max(300.0, Math.pow(this.speed, 2.0) / (2.0F * this.accelerationConstant));

         for (int i = this.getIndex(this.railProgress - this.spacing * this.trainCars, true);
            i < this.path.size() && (i == 0 || this.distances.get(i - 1) < this.railProgress + lookAheadDistance);
            i++
         ) {
            PathData pathSeg = this.path.get(i);
            this.railAheadLookup.add(pathSeg.startingPos.asLong());
            if (i > 0 && this.distances.get(i - 1) < this.railProgress && this.distances.get(i) > this.railProgress - this.spacing * this.trainCars) {
               this.atpYellowSpeed = Math.min(this.atpYellowSpeed, pathSeg.rail.railType.maxBlocksPerTick);
               this.atpRedSpeed = Math.min(this.atpRedSpeed, pathSeg.rail.railType.maxBlocksPerTick + 0.06944445F);
               effectiveLimit = Math.min(effectiveLimit, pathSeg.rail.railType.maxBlocksPerTick);
            }

            if (pathSeg.dwellTime > 0 && i != this.doorOpenedAtPlatformIndex) {
               if (this.distances.get(i) > this.railProgress) {
                  float patternSpeed = (float)Math.sqrt(2.0F * this.accelerationConstant * (this.distances.get(i) - this.railProgress));
                  if (patternSpeed < this.atpYellowSpeed) {
                     this.atpYellowSpeed = patternSpeed;
                  }

                  if (patternSpeed < targetPatternSpeed) {
                     targetPatternSpeed = patternSpeed;
                     this.atpTargetSpeed = 0.0F;
                     this.atpTargetDistance = this.distances.get(i);
                  }
               } else if (this.distances.get(i) > this.railProgress - 10.0) {
                  this.atpYellowSpeed = 0.0F;
                  targetPatternSpeed = 0.0F;
                  this.atpTargetSpeed = 0.0F;
                  this.atpTargetDistance = this.distances.get(i);
               }

               if (Math.abs(this.distances.get(i) - this.railProgress) < 5.0 && (this.doorTarget || this.doorValue > 0.0F)) {
                  this.doorOpenedAtPlatformIndex = i;
               }

               if (i + 1 < this.path.size() && pathSeg.isOppositeRail(this.path.get(i + 1))) {
                  this.atpRedSpeed = Math.min(
                     this.atpRedSpeed,
                     (float)Math.sqrt(
                        2.0F * this.accelerationConstant / this.yellowSpeedBrakeRatio * this.redSpeedBrakeRatio * (this.distances.get(i) - this.railProgress)
                     )
                  );
               }
            } else if (i > 0 && this.distances.get(i - 1) > this.railProgress) {
               float yellowPatternSpeed = (float)Math.sqrt(
                  2.0F * this.accelerationConstant * (this.distances.get(i - 1) - this.railProgress) + Math.pow(pathSeg.rail.railType.maxBlocksPerTick, 2.0)
               );
               if (yellowPatternSpeed < this.atpYellowSpeed) {
                  this.atpYellowSpeed = yellowPatternSpeed;
               }

               if (pathSeg.rail.railType.maxBlocksPerTick < effectiveLimit && yellowPatternSpeed < targetPatternSpeed) {
                  targetPatternSpeed = yellowPatternSpeed;
                  this.atpTargetSpeed = pathSeg.rail.railType.maxBlocksPerTick;
                  this.atpTargetDistance = this.distances.get(i - 1);
               }

               this.atpRedSpeed = Math.min(
                  this.atpRedSpeed,
                  (float)Math.sqrt(
                     2.0F * this.accelerationConstant / this.yellowSpeedBrakeRatio * this.redSpeedBrakeRatio * (this.distances.get(i - 1) - this.railProgress)
                        + Math.pow(pathSeg.rail.railType.maxBlocksPerTick + 0.06944445F, 2.0)
                  )
               );
            }
         }

         if (this.atpTargetDistance == (Double)this.distances.get(this.distances.size() - 1)) {
            this.atpTargetSpeed = 0.0F;
         }

         if (this.doorValue > 0.0F || this.doorTarget) {
            this.atpYellowSpeed = 0.0F;
            this.atpRedSpeed = 0.0F;
            this.atpTargetSpeed = -1.0F;
         }

         if (this.speed > this.atpRedSpeed && !this.atpCutout) {
            this.atpEmergencyBrake = true;
         }
      } else {
         stopDriving();
      }
   }

   public static boolean startDrivingRidingTrain() {
      if (activeTrain != null) {
         return false;
      } else {
         Player player = Minecraft.getInstance().player;
         if (player == null) {
            return false;
         } else {
            for (TrainClient train : ClientData.TRAINS) {
               if (train.isPlayerRiding(player)) {
                  stopDriving();
                  activeTrain = new TrainVirtualDrive(train);
                  activeTrain.railProgress = train.getRailProgress();
                  activeTrain.speed = train.getSpeed();
                  activeTrain.vehicleRidingClient
                     .startRiding(
                        player.getUUID(),
                        0.5F,
                        train.vehicleRidingClient.getPercentageZ(player.getUUID())
                     );
                  PacketVirtualDrive.sendVirtualDriveC2S(true);
                  ((cn.zbx1425.mtrsteamloco.data.VehicleRidingClientExtraSupplier) train.vehicleRidingClient).removeRider(player.getUUID());
                  Minecraft.getInstance().execute(() -> ClientData.TRAINS.add(activeTrain));
                  return true;
               }
            }

            return false;
         }
      }
   }

   public static void stopDriving() {
      if (activeTrain != null) {
         PacketVirtualDrive.sendVirtualDriveC2S(false);
         activeTrain.isRemoved = true;
         Minecraft.getInstance().execute(() -> {
            ClientData.TRAINS.remove(activeTrain);
            activeTrain = null;
         });
      }
   }

   @Override
   public Route getThisRoute() {
      return this.sourceTrain != null ? this.sourceTrain.getThisRoute() : super.getThisRoute();
   }

   @Override
   public Route getNextRoute() {
      return this.sourceTrain != null ? this.sourceTrain.getNextRoute() : super.getNextRoute();
   }

   @Override
   public Station getThisStation() {
      return this.sourceTrain != null ? this.sourceTrain.getThisStation() : super.getThisStation();
   }

   @Override
   public Station getNextStation() {
      return this.sourceTrain != null ? this.sourceTrain.getNextStation() : super.getNextStation();
   }

   public double getDistanceToNextStopForHmi() {
      if (this.nextStoppingIndex < 0 || this.nextStoppingIndex >= this.distances.size()) return 0.0;
      return Math.max(0.0, this.distances.get(this.nextStoppingIndex) - this.railProgress);
   }

   public boolean hasUpcomingStopForHmi() {
      return this.nextStoppingIndex >= 0 && this.nextStoppingIndex < this.path.size() - 1;
   }

   public float getPercentNotch() {
      return this.vdNotch < 0 ? (float)this.vdNotch / this.brakeNotches : (float)this.vdNotch / this.powerNotches;
   }

   @Override
   public int getTotalDwellTicks() {
      return 114514;
   }

   @Override
   public boolean toggleDoors() {
      if (this.speed == 0.0F) {
         if (this.doorTarget) {
            if (this.doorValue >= 1.0F) {
               this.doorTarget = false;
            }
         } else {
            this.doorTarget = true;
         }

         return true;
      } else {
         this.doorTarget = false;
         return false;
      }
   }
}
