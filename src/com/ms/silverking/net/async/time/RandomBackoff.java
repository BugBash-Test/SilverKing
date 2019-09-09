// RandomBackoff.java


package com.ms.silverking.net.async.time;


import java.util.Random;

import com.ms.silverking.thread.ThreadUtil;
import com.ms.silverking.time.SystemTimeSource;

public class RandomBackoff {
    protected final int        maxBackoffNum;
    protected final int        initialBackoffValue;
    protected final int        extraBackoffValue;
    protected int            curBackoffNum;
    protected final Random    random;
    protected int            consecutiveBackoffs;
    protected final boolean    exponential;
    protected final long    hardDeadline;
    
    private static final int    MAX_EXPONENTIAL_MAX_BACKOFF = 30;
    
    public RandomBackoff(int maxBackoffNum, 
                        int initialBackoffValue,
                        int extraBackoffValue,
                        long hardDeadline,
                        boolean exponential, 
                        long seed) {
        if (maxBackoffNum < 0 
                || exponential && maxBackoffNum > MAX_EXPONENTIAL_MAX_BACKOFF) {
            throw new RuntimeException("Bad maxBackoffNum: "+ maxBackoffNum);
        }
        this.maxBackoffNum = maxBackoffNum;
        if (initialBackoffValue <= 0) {
            throw new RuntimeException("Bad initialBackoffValue: "
                            + initialBackoffValue);
        }
        this.initialBackoffValue = initialBackoffValue;
        if (extraBackoffValue < 0) {
            throw new RuntimeException("Bad extraBackoffValue: "
                            + extraBackoffValue);
        }
        this.extraBackoffValue = extraBackoffValue;
        this.exponential = exponential;
        this.hardDeadline = hardDeadline;
        random = new Random(seed);
    }

    public RandomBackoff(int maxBackoffNum, 
                        int initialBackoffValue,
                        int extraBackoffValue,
                        long hardDeadline) {
        this( maxBackoffNum, initialBackoffValue, extraBackoffValue, 
            hardDeadline, true,
            SystemTimeSource.instance.absTimeMillis() );
    }

    public RandomBackoff(int maxBackoffNum, 
                        int initialBackoffValue,
                        long hardDeadline) {
        this(maxBackoffNum, initialBackoffValue, 0, hardDeadline);
    }

    public void reset() {
        curBackoffNum = 0;
        consecutiveBackoffs = 0;
    }
    
    protected int getCurBackoffLimit(int backoffNum) {
        if (exponential) {
            return initialBackoffValue << backoffNum;
        } else {
            return initialBackoffValue * (backoffNum + 1);
        }
    }
    
    public int getRandomBackoff(int backoffNum) {
        return random.nextInt( getCurBackoffLimit(backoffNum) ) + extraBackoffValue;
    }
    
    protected int getRandomBackoff() {
        return random.nextInt( getCurBackoffLimit(curBackoffNum) ) + extraBackoffValue;
    }
    
    public int backoff(Object waitObj) {
        return backoff(waitObj, true);
    }
    
    public int backoff(Object waitObj, boolean waitHere) {
        int    backoffTime;
        
        backoffTime = getRandomBackoff();
        curBackoffNum = Math.min(curBackoffNum + 1, maxBackoffNum);
        //System.out.println(curBackoffNum +"\t"+ maxBackoffNum +"\t"+ backoffTime);
        consecutiveBackoffs++;
        if (waitHere) {
            if (hardDeadline > 0) {
                backoffTime = (int)Math.min(backoffTime, hardDeadline - SystemTimeSource.instance.absTimeMillis());
            }
            if (waitObj == null) {
                ThreadUtil.sleep(backoffTime);
            } else {
                synchronized (waitObj) {
                    try {
                        waitObj.wait(backoffTime);
                    } catch (InterruptedException ie) {
                    }
                }
            }
        }
        return backoffTime;
    }

    public int backoff() {
        return backoff(null);
    }
    
    public int backoffTime() {
        return backoff(null, false);
    }
    
    public int getConsecutiveBackoffs() {
        return consecutiveBackoffs;
    }
    
    public boolean maxBackoffExceeded() {
        return consecutiveBackoffs >= maxBackoffNum;
    }

    @Override
    public String toString() {
        return String.format("%s[curBackoffNum=%d, maxBackoffNum=%d]",this.getClass().getSimpleName(), curBackoffNum, maxBackoffNum);
    }
}
