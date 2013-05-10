/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred;

import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.server.jobtracker.TaskTracker;

/**
 * A {@link TaskScheduler} Delay assign job.
 */
class DelayTaskScheduler extends TaskScheduler {
  
  private static final int MIN_CLUSTER_SIZE_FOR_PADDING = 3;
  public static final Log LOG = LogFactory.getLog(DelayTaskScheduler.class);
  
  protected JobQueueJobInProgressListener jobQueueJobInProgressListener;
  protected EagerTaskInitializationListener eagerTaskInitializationListener;
  private float padFraction;
  FileWriter writer;
  
  public DelayTaskScheduler() {
    this.jobQueueJobInProgressListener = new JobQueueJobInProgressListener();
    try{
    	writer = new FileWriter("/usr/local/hadoop/localilty.csv");
    }
    catch(IOException e)
	{
	     e.printStackTrace();
	} 
  }
  
  @Override
  public synchronized void start() throws IOException {
    super.start();
    taskTrackerManager.addJobInProgressListener(jobQueueJobInProgressListener);
    eagerTaskInitializationListener.setTaskTrackerManager(taskTrackerManager);
    eagerTaskInitializationListener.start();
    taskTrackerManager.addJobInProgressListener(
        eagerTaskInitializationListener);
  }
  
  @Override
  public synchronized void terminate() throws IOException {
    if (jobQueueJobInProgressListener != null) {
      taskTrackerManager.removeJobInProgressListener(
          jobQueueJobInProgressListener);
    }
    if (eagerTaskInitializationListener != null) {
      taskTrackerManager.removeJobInProgressListener(
          eagerTaskInitializationListener);
      eagerTaskInitializationListener.terminate();
    }
    super.terminate();
  }
  
  @Override
  public synchronized void setConf(Configuration conf) {
    super.setConf(conf);
    padFraction = conf.getFloat("mapred.jobtracker.taskalloc.capacitypad", 
                                 0.01f);
    this.eagerTaskInitializationListener =
      new EagerTaskInitializationListener(conf);
  }

  /*by wk, comparetor*/
  private static final Comparator<JobInProgress> failComparator =
		    new Comparator<JobInProgress>() {
		      @Override
		      public int compare(JobInProgress t1, JobInProgress t2) {
		        if (t1 == null) return -1;
		        if (t2 == null) return 1;
		        
		        int running = t1.runningMaps() + t1.runningReduces() - t2.runningMaps() - t2.runningReduces();
		        return (running == 0) ? (t1.getJobID().getId() - t2.getJobID().getId())
		            : running;
		      }
		    };
  
  
  @Override
  public synchronized List<Task> assignTasks(TaskTracker taskTracker)
      throws IOException {
    // Check for JT safe-mode
    if (taskTrackerManager.isInSafeMode()) {
      LOG.info("JobTracker is in safe-mode, not scheduling any tasks.");
      return null;
    } 

    TaskTrackerStatus taskTrackerStatus = taskTracker.getStatus(); 
    ClusterStatus clusterStatus = taskTrackerManager.getClusterStatus();
    final int numTaskTrackers = clusterStatus.getTaskTrackers();
    final int clusterMapCapacity = clusterStatus.getMaxMapTasks();
    final int clusterReduceCapacity = clusterStatus.getMaxReduceTasks();

    Collection<JobInProgress> jobQueue =
      jobQueueJobInProgressListener.getJobQueue();
    
    /*by wk, sort by running tasks increasing order*/
    Collections.sort((ArrayList<JobInProgress>)jobQueue,failComparator);
    
  
    //
    // Get map + reduce counts for the current tracker.
    //
    final int trackerMapCapacity = taskTrackerStatus.getMaxMapSlots();
    final int trackerReduceCapacity = taskTrackerStatus.getMaxReduceSlots();
    final int trackerRunningMaps = taskTrackerStatus.countMapTasks();
    final int trackerRunningReduces = taskTrackerStatus.countReduceTasks();

    // Assigned tasks
    List<Task> assignedTasks = new ArrayList<Task>();

    //
    // Compute (running + pending) map and reduce task numbers across pool
    //
    int remainingReduceLoad = 0;
    int remainingMapLoad = 0;
    synchronized (jobQueue) {
      for (JobInProgress job : jobQueue) {
        if (job.getStatus().getRunState() == JobStatus.RUNNING) {
          remainingMapLoad += (job.desiredMaps() - job.finishedMaps());
          if (job.scheduleReduces()) {
            remainingReduceLoad += 
              (job.desiredReduces() - job.finishedReduces());
          }
        }
      }
    }

    // Compute the 'load factor' for maps and reduces
    double mapLoadFactor = 0.0;
    if (clusterMapCapacity > 0) {
      mapLoadFactor = (double)remainingMapLoad / clusterMapCapacity;
    }
    double reduceLoadFactor = 0.0;
    if (clusterReduceCapacity > 0) {
      reduceLoadFactor = (double)remainingReduceLoad / clusterReduceCapacity;
    }
        
    //
    // In the below steps, we allocate first map tasks (if appropriate),
    // and then reduce tasks if appropriate.  We go through all jobs
    // in order of job arrival; jobs only get serviced if their 
    // predecessors are serviced, too.
    //

    //
    // We assign tasks to the current taskTracker if the given machine 
    // has a workload that's less than the maximum load of that kind of
    // task.
    // However, if the cluster is close to getting loaded i.e. we don't
    // have enough _padding_ for speculative executions etc., we only 
    // schedule the "highest priority" task i.e. the task from the job 
    // with the highest priority.
    //
    
    final int trackerCurrentMapCapacity = 
      Math.min((int)Math.ceil(mapLoadFactor * trackerMapCapacity), 
                              trackerMapCapacity);
    int availableMapSlots = trackerCurrentMapCapacity - trackerRunningMaps;
    boolean exceededMapPadding = false;
    if (availableMapSlots > 0) {
      exceededMapPadding = 
        exceededPadding(true, clusterStatus, trackerMapCapacity);
    }
    
    int numLocalMaps = 0;
    int numNonLocalMaps = 0;
    scheduleMaps:
    for (int i=0; i < availableMapSlots; i = (++i % (availableMapSlots))) {
      synchronized (jobQueue) {
      for (JobInProgress job : jobQueue) {
          if (job.getStatus().getRunState() != JobStatus.RUNNING) {
            continue;
          }

          Task t = null;
          
          // Try to schedule a node-local or rack-local Map task
          t = 
            job.obtainNewNodeLocalMapTask(taskTrackerStatus, 
                numTaskTrackers, taskTrackerManager.getNumberOfUniqueHosts());/*by wk, only local*/
          if (t != null) {
            assignedTasks.add(t);
            ++numLocalMaps;
            job.setSkipCount(0);
            
            // Don't assign map tasks to the hilt!
            // Leave some free slots in the cluster for future task-failures,
            // speculative tasks etc. beyond the highest priority job
            if (exceededMapPadding) {
              break scheduleMaps;
            }
           
            // Try all jobs again for the next Map task 
            break;
          }
          else
          {
        	  int M = numTaskTrackers/(trackerMapCapacity + trackerReduceCapacity);
        	  int R = 3;
        	  double lamda = 0.95;
        	  int N = job.desiredMaps();
        	  double D = (-1) * ((double)M/R) * Math.log((1-lamda)*N/(1 + (1-lamda)*N));
              	  
        	  if(job.getSkipCount() > 0.23*4)
        	  {
                  // Try to schedule a node-local or rack-local Map task
                  t = 
                  job.obtainNewNonLocalMapTask(taskTrackerStatus, numTaskTrackers,
                                   taskTrackerManager.getNumberOfUniqueHosts());
          
                  if (t != null) {
                  assignedTasks.add(t);
                  ++numNonLocalMaps;
            
                  // We assign at most 1 off-switch or speculative task
                  // This is to prevent TaskTrackers from stealing local-tasks
                  // from other TaskTrackers.
                  break scheduleMaps;
                  }
        	  }
        	  else
        		  job.setSkipCount((job.getSkipCount() + 1));
          }
        }
      }
    }
    int assignedMaps = assignedTasks.size();

    //
    // Same thing, but for reduce tasks
    // However we _never_ assign more than 1 reduce task per heartbeat
    //
    final int trackerCurrentReduceCapacity = 
      Math.min((int)Math.ceil(reduceLoadFactor * trackerReduceCapacity), 
               trackerReduceCapacity);
    final int availableReduceSlots = 
      Math.min((trackerCurrentReduceCapacity - trackerRunningReduces), 1);
    boolean exceededReducePadding = false;
    if (availableReduceSlots > 0) {
      exceededReducePadding = exceededPadding(false, clusterStatus, 
                                              trackerReduceCapacity);
      synchronized (jobQueue) {
        for (JobInProgress job : jobQueue) {
          if (job.getStatus().getRunState() != JobStatus.RUNNING ||
              job.numReduceTasks == 0) {
            continue;
          }

          Task t = 
            job.obtainNewReduceTask(taskTrackerStatus, numTaskTrackers, 
                                    taskTrackerManager.getNumberOfUniqueHosts()
                                    );
          if (t != null) {
            assignedTasks.add(t);
            break;
          }
          
          // Don't assign reduce tasks to the hilt!
          // Leave some free slots in the cluster for future task-failures,
          // speculative tasks etc. beyond the highest priority job
          if (exceededReducePadding) {
            break;
          }
        }
      }
    }
    
    if (true) {
    	try
    	{    
    	    writer.append(Integer.toString(numLocalMaps));
    	    writer.append(',');
    	    writer.append(Integer.toString(numNonLocalMaps));
    	    writer.append('\n');
    	    
    	    //generate whatever data you want
     
    	    writer.flush();
    	}
    	catch(IOException e)
    	{
    	     e.printStackTrace();
    	} 
    	  	
      LOG.info("Task assignments for " + taskTrackerStatus.getTrackerName() + " --> " +
                "[" + mapLoadFactor + ", " + trackerMapCapacity + ", " + 
                trackerCurrentMapCapacity + ", " + trackerRunningMaps + "] -> [" + 
                (trackerCurrentMapCapacity - trackerRunningMaps) + ", " +
                assignedMaps + " (" + numLocalMaps + ", " + numNonLocalMaps + 
                ")] [" + reduceLoadFactor + ", " + trackerReduceCapacity + ", " + 
                trackerCurrentReduceCapacity + "," + trackerRunningReduces + 
                "] -> [" + (trackerCurrentReduceCapacity - trackerRunningReduces) + 
                ", " + (assignedTasks.size()-assignedMaps) + "]");
    }

    return assignedTasks;
  }

  private boolean exceededPadding(boolean isMapTask, 
                                  ClusterStatus clusterStatus, 
                                  int maxTaskTrackerSlots) { 
    int numTaskTrackers = clusterStatus.getTaskTrackers();
    int totalTasks = 
      (isMapTask) ? clusterStatus.getMapTasks() : 
        clusterStatus.getReduceTasks();
    int totalTaskCapacity = 
      isMapTask ? clusterStatus.getMaxMapTasks() : 
                  clusterStatus.getMaxReduceTasks();

    Collection<JobInProgress> jobQueue =
      jobQueueJobInProgressListener.getJobQueue();

    boolean exceededPadding = false;
    synchronized (jobQueue) {
      int totalNeededTasks = 0;
      for (JobInProgress job : jobQueue) {
        if (job.getStatus().getRunState() != JobStatus.RUNNING ||
            job.numReduceTasks == 0) {
          continue;
        }

        //
        // Beyond the highest-priority task, reserve a little 
        // room for failures and speculative executions; don't 
        // schedule tasks to the hilt.
        //
        totalNeededTasks += 
          isMapTask ? job.desiredMaps() : job.desiredReduces();
        int padding = 0;
        if (numTaskTrackers > MIN_CLUSTER_SIZE_FOR_PADDING) {
          padding = 
            Math.min(maxTaskTrackerSlots,
                     (int) (totalNeededTasks * padFraction));
        }
        if (totalTasks + padding >= totalTaskCapacity) {
          exceededPadding = true;
          break;
        }
      }
    }

    return exceededPadding;
  }

  @Override
  public synchronized Collection<JobInProgress> getJobs(String queueName) {
    return jobQueueJobInProgressListener.getJobQueue();
  }  
}
