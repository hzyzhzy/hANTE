package cn.zbx1425.mtrsteamloco.game;

import it.unimi.dsi.fastutil.floats.FloatArrayFIFOQueue;

public class DelayedValue {
   private int delayTicks;
   private FloatArrayFIFOQueue buffer;
   private float elapsedDeltaTicks;

   public DelayedValue(double delay) {
      this.setDelay(delay);
   }

   public void setDelay(double delay) {
      this.delayTicks = (int)(delay * 20.0);
      if (this.buffer != null) {
         this.buffer.clear();
      } else {
         this.buffer = new FloatArrayFIFOQueue(this.delayTicks);
      }

      this.elapsedDeltaTicks = 0.0F;
   }

   public float setAndGet(float newValue, float deltaTicks) {
      this.elapsedDeltaTicks += deltaTicks;
      if (this.elapsedDeltaTicks < 1.0F) {
         return this.buffer.isEmpty() ? newValue : this.buffer.firstFloat();
      } else {
         this.elapsedDeltaTicks--;
         this.buffer.enqueue(newValue);
         if (this.buffer.size() > this.delayTicks) {
            this.buffer.dequeueFloat();
         }

         return this.buffer.firstFloat();
      }
   }
}
