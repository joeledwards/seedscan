/*
 * Copyright 2011, United States Geological Survey or
 * third-party contributors as indicated by the @author tags.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/  >.
 *
 */
package asl.seedscan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import asl.metadata.Station;
import asl.metadata.MetaServer;
import asl.seedscan.database.*;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.List;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScanManager
{
    private static final Logger logger = LoggerFactory.getLogger(asl.seedscan.ScanManager.class);

    private Scan scan = null;
    private ConcurrentLinkedQueue<Runnable> taskQueue = null;
    private boolean running = true;

    public ScanManager(MetricReader reader, MetricInjector injector, List<Station> stationList, Scan scan, MetaServer metaServer)

    {
        this.scan = scan;

        int threadCount = Runtime.getRuntime().availableProcessors();

        logger.info("Number of Threads to Use = [{}]", threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (Station station : stationList) {
            if (passesFilter(station)) {
                logger.debug("Add station={} to the task queue", station);
                logger.info("Add station={} to the task queue", station);
                executor.execute( new Scanner(reader, injector, station, scan, metaServer) );
            }
            else {
                logger.debug("station={} Did NOT pass filter for scan={}", station, scan.getName());
            }
        }
        executor.shutdown();
/**
    awaitTermination(long timeout, TimeUnit unit) - Blocks until all tasks have completed execution 
      after a shutdown request, or the timeout occurs, or the current thread is interrupted, whichever happens first.
**/
        while (executor.isTerminated() == false) { // Hang out here until all worker threads have finished
        }
        logger.info("ALL THREADS HAVE FINISHED");
    }

    private boolean passesFilter(Station station) {
        if (scan.getNetworks() != null){
            if (!scan.getNetworks().filter(station.getNetwork())) {
                return false;
            }
        }
        if (scan.getStations() != null){
            if (!scan.getStations().filter(station.getStation())) {
                return false;
            }
        }
        return true;
    }

}
