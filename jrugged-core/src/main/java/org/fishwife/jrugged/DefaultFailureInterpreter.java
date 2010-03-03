/* Copyright 2009 Comcast Interactive Media, LLC.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.fishwife.jrugged;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Fails if exception frequencies surpasses a declared number per time
 * threshold.
 */
public final class DefaultFailureInterpreter implements FailureInterpreter {

    private Set<Class<? extends Throwable>> trip = new HashSet<Class<? extends Throwable>>();
    private Set<Class<? extends Throwable>> ignore = new HashSet<Class<? extends Throwable>>();
    private int frequency = 0;
    private long time = 0;
    private TimeUnit unit;
    // tracks times when exceptions occurred
    private List<Long> errorTimes = Collections
            .synchronizedList(new LinkedList<Long>());

	private static Class[] defaultIgnore = { };
	private static Class[] defaultTrip = { Throwable.class };

    public DefaultFailureInterpreter() {
		setIgnore(defaultIgnore);
		setTrip(defaultTrip);
	}

	public DefaultFailureInterpreter(int frequency, long time,
									   TimeUnit unit) {
		setIgnore(defaultIgnore);
		setTrip(defaultTrip);
		setFrequency(frequency);
		setTime(time);
		setUnit(unit);
	}

	public DefaultFailureInterpreter(Class<? extends Throwable>[] ignore) {
		setIgnore(ignore);
		setTrip(defaultTrip);
	}

	public DefaultFailureInterpreter(Class<? extends Throwable>[] ignore,
									   int frequency, long time,
									   TimeUnit unit) {
		setIgnore(ignore);
		setTrip(defaultTrip);
		setFrequency(frequency);
		setTime(time);
		setUnit(unit);
	}

	public DefaultFailureInterpreter(Class<? extends Throwable>[] ignore,
									   Class<? extends Throwable>[] trip) {
		setIgnore(ignore);
		setTrip(trip);
	}

	public DefaultFailureInterpreter(Class<? extends Throwable>[] ignore,
									   Class<? extends Throwable>[] trip,
									   int frequency, long time,
									   TimeUnit unit) {
		setIgnore(ignore);
		setTrip(trip);
		setFrequency(frequency);
		setTime(time);
		setUnit(unit);
	}

	private void checkForProblematicExceptionSets() {
		for(Class cignore : ignore) {
			for(Class ctrip : trip) {
				if (cignore.isAssignableFrom(ctrip)) {
					throw new IllegalArgumentException(String.format("Tripping exception: %s, is a subclass of an ignored exception: %s", ctrip.getName(), cignore.getName()));
				}
			}
		}
	}

    private boolean hasTimeConditions() {
        return this.frequency > 0 && this.time > 0;
    }
	
	public boolean shouldTrip(Throwable cause) {
		for(Class clazz : ignore) {
			if (clazz.isInstance(cause)) {
				return false;
			}
		}

		// if Exception is of specified type, and time conditions exist,
		// keep circuit open unless exception threshold has passed
        
		// if Exception if of specified type, and no time conditions exist
		// open circuit
		boolean isInstance = false;
		// if Exception is not of specified type(s), keep circuit open
		for (Class clazz : trip) {
			if (clazz.isInstance(cause)) {
				isInstance = true;
				break;
			}
		}
		
		if (isInstance && hasTimeConditions()) {
			errorTimes.add(System.currentTimeMillis());

			// calculates time for which we remove any errors before
			final long removeTimesBeforeMillis = System.currentTimeMillis()
				- this.unit.toMillis(this.time);
			
			// removes errors before cutoff 
			// (could we speed this up by using binary search to find the entry point,
			// then removing any items before that point?)
			for (final Iterator<Long> i = this.errorTimes.iterator(); i.hasNext();) {
				final Long time = i.next();
				if (time < removeTimesBeforeMillis) {
					i.remove();
				} else {
					// the list is sorted by time, if I didn't remove this item
					// I won't be remove any after it either
					break;
				}
			}
			
			// Trip if the exception count has passed the limit
			return (errorTimes.size() > frequency);
		} 
		return isInstance;
	}

    
    public <V> V invoke(Callable<V> c) throws Exception {
        try {
            return c.call();
        } catch (Exception e) {
            /*
             * if this exception is in the ignore list - propagate it through
             * and do nothing with timers or counts.
             */
            if (ignore.contains(e)) {
                throw e;
            }

            // if Exception is of specified type, and time conditions exist,
            // keep circuit open unless exception threshold has passed
            
            // if Exception if of specified type, and no time conditions exist
            // close circuit
            boolean isInstance = false;
            // if Exception is not of specified type(s), keep circuit open
            for (Class trip : this.trip) {
                if (trip.isInstance(e)) {
                    isInstance = true;
                    break;
                }
            }
            final boolean hasTimeConditions = this.hasTimeConditions();
            if (isInstance && hasTimeConditions) {
                errorTimes.add(System.currentTimeMillis());

                // calculates time for which we remove any errors before
                final long removeTimesBeforeMillis = System.currentTimeMillis()
                        - this.unit.toMillis(this.time);

                // removes errors before cutoff 
                // (could we speed this up by using binary search to find the entry point,
                // then removing any items before that point?)
                for (final Iterator<Long> i = this.errorTimes.iterator(); i.hasNext();) {
                    final Long time = i.next();
                    if (time < removeTimesBeforeMillis) {
                        i.remove();
                    } else {
                        // the list is sorted by time, if I didn't remove this item
                        // I won't be remove any after it either
                        break;
                    }
                }

                // The exception count hasn't passed the limit, so the circuit
                // stays open
                if (errorTimes.size() < this.frequency)
                    throw new CircuitShouldStayOpenException(e);
                else
                    throw e;
            } else if (isInstance) {
                throw e;
            } else {
                throw new CircuitShouldStayOpenException(e);
            }
        }
    }
    
    /**
     * get the value of trip
     * @return the value of trip
     */
    public Set<Class<? extends Throwable>> getTrip(){
        return this.trip;
    }

    /**
     * get the value of trip
     *
     * @param search the exception class I am looking for
     * @return boolean the value of trip
     */
    public boolean isTrip(Class<? extends Throwable> search){
        if (this.trip.contains(search)) {
            for (Class<? extends Throwable> trip : this.trip) {
                if (trip.equals(search)) {
                    return true;
                }
            }

        }
        return false;
    }

    /**
     * set a new value to errorCount
     * @param trip the new value to be used
     */
    public synchronized void setTrip(Class<? extends Throwable>[] trip) {
		this.trip = new HashSet<Class<? extends Throwable>>(Arrays.asList(trip));
		checkForProblematicExceptionSets();
    }

    /**
     * get the value of trip
     * @return the value of trip
     */
    public Set<Class<? extends Throwable>> getIgnore(){
        return this.ignore;
    }

    /**
     * get the value of trip
     *
     * @param search the exception class I am looking for
     * @return boolean the value of trip
     */
    public boolean isIgnore(Class<? extends Throwable> search){
        if (this.trip.contains(search)) {
            for (Class<? extends Throwable> trip : this.trip) {
                if (trip.equals(search)) {
                    return true;
                }
            }

        }
        return false;
    }

    /**
     * set a new value to ignore
     * @param ignore the new value to be used
     */
    public synchronized void setIgnore(Class<? extends Throwable>[] ignore) {
		this.ignore = new HashSet<Class<? extends Throwable>>(Arrays.asList(ignore));
		checkForProblematicExceptionSets();
    }

    /**
     * get the value of frequency
     * @return the value of frequency
     */
    public int getFrequency(){
        return this.frequency;
    }
    /**
     * set a new value to errorCount
     * @param frequency the new value to be used
     */
    public void setFrequency(int frequency) {
        this.frequency=frequency;
    }

    /**
     * get the value of time
     * @return the value of time
     */
    public long getTime(){
        return this.time;
    }
    /**
     * set a new value to time
     * @param time the new value to be used
     */
    public void setTime(long time) {
        this.time=time;
    }

    /**
     * get the value of unit
     * @return the value of unit
     */
    public TimeUnit getUnit(){
        return this.unit;
    }
    /**
     * set a new value to unit
     * @param unit the new value to be used
     */
    public void setUnit(TimeUnit unit) {
        this.unit=unit;
    }
}
