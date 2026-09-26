package com.vincenthzr.locationspoofer.xposed.hooks
import org.junit.Assert.assertEquals
import org.junit.Test
class StepPhaseClockTest {
    @Test fun stopWithinSameClockTickMustResetContinuity() {
        val c = StepPhaseClock()
        c.advance(1, 0, true) { _, _ -> 0.0 }
        assertEquals(2352.0, c.advance(1, 1000, true) { _, _ -> 2.0 }, 0.0)
        c.advance(1, 1000, false) { _, _ -> 0.0 }
        c.advance(1, 1000, true) { _, _ -> 0.0 }
        assertEquals(2352.0, c.advance(1, 1500, true) { _, _ -> 1.0 }, 0.0)
        assertEquals(2353.0, c.advance(1, 2000, true) { _, _ -> 1.0 }, 0.0)
    }
    @Test fun cadenceChangeOnlyAffectsFutureSteps() {
        val c=StepPhaseClock()
        fun tick(t:Long,rate:Double)=c.advance(1,t,true){a,b->(b-a)*rate/60000.0}
        tick(0,190.0)
        assertEquals(2350+190.0/60,tick(1000,190.0),1e-9)
        assertEquals(2350+190.0/60+2,tick(2000,120.0),1e-9)
    }
    @Test fun pauseResumeAndStallNeverCatchUp() {
        val c=StepPhaseClock()
        fun tick(t:Long,on:Boolean)=c.advance(1,t,on){a,b->(b-a)*2.0/1000}
        tick(0,true);assertEquals(2352.0,tick(1000,true),0.0)
        tick(1500,false);tick(2500,false)
        assertEquals(2352.0,tick(3000,true),0.0)
        assertEquals(2354.0,tick(4000,true),0.0)
        assertEquals(2354.0,tick(10000,true),0.0)
    }
    @Test fun sharedClockDoesNotDoubleCountOrRewind() {
        val c=StepPhaseClock()
        c.advance(1,0,true){_,_->0.0}
        assertEquals(2352.0,c.advance(1,1000,true){_,_->2.0},0.0)
        assertEquals(2352.0,c.advance(1,1000,true){_,_->2.0},0.0)
        assertEquals(2352.0,c.advance(1,999,true){_,_->2.0},0.0)
        assertEquals(2352.0,c.advance(2,1100,true){_,_->2.0},0.0)
    }
}
