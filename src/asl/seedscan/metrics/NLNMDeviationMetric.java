/*
 * Copyright 2012, United States Geological Survey or
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
package asl.seedscan.metrics;

import java.awt.Color;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.nio.ByteBuffer;

import java.awt.BasicStroke;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Calendar;

import asl.metadata.Channel;
import asl.metadata.Station;
import asl.util.Hex;
import asl.util.PlotMaker;
import asl.util.PlotMaker2;
import asl.util.Trace;

import timeutils.Timeseries;

/**
 * NLNMDeviationMetric - Compute Difference (over specified range of periods = powerband) between
 *                       the power spectral density (psd) of a channel and the NLNM.
 */
public class NLNMDeviationMetric
extends PowerBandMetric
{
    private static final Logger logger = LoggerFactory.getLogger(asl.seedscan.metrics.NLNMDeviationMetric.class);

    @Override public long getVersion()
    {
        return 1;
    }

    @Override public String getBaseName()
    {
        return "NLNMDeviationMetric";
    }

    public NLNMDeviationMetric(){
        super();
        addArgument("nlnm-modelfile");
        addArgument("nhnm-modelfile");
    }
    
    private static String NLNMFile;
    private static String NHNMFile;
    
    private static NoiseModel NLNM;
    private static NoiseModel NHNM;

    private PlotMaker2 plotMaker = null;

    public void process()
    {
        System.out.format("\n              [ == Metric %s == ]    [== Station %s ==]    [== Day %s ==]\n", 
                          getName(), getStation(), getDay() );
        
        try {
	        NLNMFile = get("nlnm-modelfile");
	        NHNMFile = get("nhnm-modelfile");
        	getNLNM();
        	getNHNM();
        }
        catch(Exception e) {
            logger.error("Failed attempt to read noise models:" + e.getMessage());
        }

    // Low noise model (NLNM) MUST exist or we can't compute the metric (NHNM is optional)
        if (!getNLNM().isValid()) {
            logger.warn("Low Noise Model (NLNM) does NOT exist --> Skip Metric");
            return;
        }

// Get all LH channels in metadata
        List<Channel> channels = stationMeta.getChannelArray("LH"); 

        if (channels == null || channels.size() == 0) {
            System.out.format("== %s: No LH? channels found for station=[%s]\n", getName(), getStation() );
            return;
        }

   // Loop over channels, get metadata & data for channel and Calculate Metric

        for (Channel channel : channels){

         // Check to see that we have data + metadata & see if the digest has changed wrt the database:
         // Update: At this point we KNOW we have metadata since it is driving the channel loop

            ByteBuffer digest = metricData.valueDigestChanged(channel, createIdentifier(channel), getForceUpdate() );

            if (digest == null) {
                System.out.format("%s INFO: Data and metadata have NOT changed for this channel:%s --> Skipping\n"
                                  ,getName(), channel);
                continue;
            }

            double result = computeMetric(channel);
            if (result == NO_RESULT) {
                // Do nothing --> skip to next channel
            }
            else {
                metricResult.addResult(channel, result, digest);
            }

        }// end foreach channel


        // If we didn't add any channel-panels below, then plotMaker should still be null
        if (getMakePlots() && (plotMaker != null) ) {
            BasicStroke stroke = new BasicStroke(4.0f);
            for (int iPanel=0; iPanel<3; iPanel++){
                plotMaker.addTraceToPanel( new Trace(getNLNM().getPeriods(), getNLNM().getPowers(),
                		"NLNM", Color.black, stroke), iPanel);
                if (getNHNM().isValid()) {
                    plotMaker.addTraceToPanel( new Trace(getNHNM().getPeriods(), getNHNM().getPowers(),
                    		"NHNM", Color.black, stroke), iPanel);
                }
            }
            // outputs/2012160.IU_ANMO.nlnm-dev.png
            final String pngName   = String.format("%s.%s.png", getOutputDir(), "nlnm-dev" );
            plotMaker.writePlot(pngName);
        }

    } // end process()


    private double computeMetric(Channel channel) {

     // Compute/Get the 1-sided psd[f] using Peterson's algorithm (24 hrs, 13 segments, etc.)

        CrossPower crossPower = getCrossPower(channel, channel);
        double[] psd  = crossPower.getSpectrum();
        double df     = crossPower.getSpectrumDeltaF();

     // nf = number of positive frequencies + DC (nf = nfft/2 + 1, [f: 0, df, 2df, ...,nfft/2*df] )
        int nf        = psd.length;
        double freq[] = new double[nf];

     // Fill freq array & Convert spectrum to dB
        for ( int k = 0; k < nf; k++){
            freq[k] = (double)k * df;
            psd[k]  = 10.*Math.log10(psd[k]);
        }

     // Convert psd[f] to psd[T]
     // Reverse freq[] --> per[] where per[0]=shortest T and per[nf-2]=longest T:

        double[] per    = new double[nf];
        double[] psdPer = new double[nf];
     // per[nf-1] = 1/freq[0] = 1/0 = inf --> set manually:
        per[nf-1] = 0;  
        for (int k = 0; k < nf-1; k++){
            per[k]     = 1./freq[nf-k-1];
            psdPer[k]  = psd[nf-k-1];
        }
        double Tmin  = per[0];    // Should be = 1/fNyq = 2/fs = 0.1 for fs=20Hz
        double Tmax  = per[nf-2]; // Should be = 1/df = Ndt

        String outFile; // Use for outputting spectra arrays (in testing)

        //outFile = channel.toString() + ".psd.T";
        //Timeseries.timeoutXY(per, psdPer, outFile);

     // Interpolate the smoothed psd to the periods of the NLNM Model:
        double psdInterp[] = Timeseries.interpolate(per, psdPer, getNLNM().getPeriods());

        //outFile = channel.toString() + ".psd.Fsmooth.T.Interp";
        //Timeseries.timeoutXY(NLNMPeriods, psdInterp, outFile);

        PowerBand band    = getPowerBand();
        double lowPeriod  = band.getLow();
        double highPeriod = band.getHigh();

        if (!checkPowerBand(lowPeriod, highPeriod, Tmin, Tmax)){
            System.out.format("%s powerBand Error: Skipping channel:%s\n", getName(), channel);
            return NO_RESULT;
        }

    // Compute deviation from NLNM within the requested period band:
        double deviation = 0;
        int nPeriods = 0;
        for (int k = 0; k < getNLNM().getPeriods().length; k++){
            if (getNLNM().getPeriods()[k] >  highPeriod){
                break;
            }
            else if (getNLNM().getPeriods()[k] >= lowPeriod){
                double difference = psdInterp[k] - getNLNM().getPowers()[k];
                //deviation += Math.sqrt( Math.pow(difference, 2) );
                deviation += difference;
                nPeriods++;
            }
        }

        if (nPeriods == 0) {
            StringBuilder message = new StringBuilder();
            message.append(String.format("NLNMDeviation Error: Requested band [%f - %f] contains NO periods within NLNM\n"
                ,lowPeriod, highPeriod) );
            throw new RuntimeException(message.toString());
        }
        deviation = deviation/(double)nPeriods;

        if (getMakePlots()) { 
            makePlots(channel, getNLNM().getPeriods(), psdInterp);
        }

        return deviation;

    } // end computeMetric()

    private void makePlots(Channel channel, double xdata[], double ydata[]) {
        if (xdata.length != ydata.length) {
            throw new RuntimeException(String.format("%s makePlots() Error: xdata.len=%d != ydata.len=%d",
                                       getName(), xdata.length, ydata.length) );
        }
        if (plotMaker == null) {
            String date = String.format("%04d%03d", metricResult.getDate().get(Calendar.YEAR),
                                                    metricResult.getDate().get(Calendar.DAY_OF_YEAR) );
            final String plotTitle = String.format("[ Date: %s ] [ Station: %s ] NLNM-Deviation", date, getStation() );
            plotMaker = new PlotMaker2(plotTitle);
            plotMaker.initialize3Panels("LHZ", "LH1/LHN", "LH2/LHE");
        }
        int iPanel;
        Color color = Color.black;
        BasicStroke stroke = new BasicStroke(2.0f);

        if  (channel.getChannel().equals("LHZ")) {
            iPanel = 0;
        }
        else if (channel.getChannel().equals("LH1") || channel.getChannel().equals("LHN") ) {
            iPanel = 1;
        }
        else if (channel.getChannel().equals("LH2") || channel.getChannel().equals("LHE") ) {
            iPanel = 2;
        }
        else { // ??
            throw new RuntimeException(String.format("%s makePlots() Don't know how to plot channel=%s", 
                                       getName(), channel) );
        }

        if (channel.getLocation().equals("00")) {
            color  = Color.green;
        }
        else if (channel.getLocation().equals("10")) {
            color  = Color.red;
        }
        else { // ??
        }

        plotMaker.addTraceToPanel( new Trace(xdata, ydata, channel.toString(), color, stroke), iPanel);
    }
    
    private static NoiseModel getNHNM()
    {
    	if (NHNM == null)
    		initNHNM();
    	
    	return NHNM;
    }
    
    private synchronized static void initNHNM()
    {
    	if (NHNM == null)
    	{
    		NHNM = new NoiseModel();
    		readNHNM(NHNMFile, NHNM);
    	}
    }
    
    public static NoiseModel getNLNM()
    {
    	if (NLNM == null)
    		initHLNM();
    	
    	return NLNM;
    }
    
    public synchronized static void initHLNM()
    {
    	if (NLNM == null)
    	{
    		NLNM = new NoiseModel();
    		readNLNM(NLNMFile, NLNM);
    	}
    }


/** readNLNM() - Read in Peterson's NewLowNoiseModel from file specified in config.xml
 **       e.g., <cfg:argument cfg:name="nlnm-modelfile">/Users/mth/mth/Projects/xs0/NLNM.ascii/</cfg:argument>
 **             NLNM Periods will be read into NLNMPeriods[]
 **             NLNM Powers  will be read into NLNMPowers[]
 **/
    private synchronized static void readNLNM(String fileName, NoiseModel noiseModel) {

    logger.info("readNLNM Read in NLNM model from file=[{}]", fileName);

   // First see if the file exists
        if (!(new File(fileName).exists())) {
            logger.error("NLNM file={} does NOT exist!", fileName);
            return;
        }
   // Temp ArrayList(s) to read in unknown number of (x,y) pairs:
        ArrayList<Double> tmpPers = new ArrayList<Double>();
        ArrayList<Double> tmpPows = new ArrayList<Double>();
        BufferedReader br = null;
        try {
            String line;
            br = new BufferedReader(new FileReader(fileName));
            while ((line = br.readLine()) != null) {
                String[] args = line.trim().split("\\s+") ;
                if (args.length != 2) {
                    String message = "==Error reading NLNM: got " + args.length + " args on one line!";
                    throw new RuntimeException(message);
                }
                tmpPers.add( Double.valueOf(args[0].trim()).doubleValue() );
                tmpPows.add( Double.valueOf(args[1].trim()).doubleValue() );
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (br != null)br.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        Double[] modelPeriods  = tmpPers.toArray(new Double[]{});
        Double[] modelPowers   = tmpPows.toArray(new Double[]{});

        noiseModel.periods = new double[modelPeriods.length];
        noiseModel.powers  = new double[modelPowers.length];

        for (int i=0; i<modelPeriods.length; i++){
            noiseModel.periods[i] = modelPeriods[i];
            noiseModel.powers[i]  = modelPowers[i];
        }
        
        noiseModel.valid = true;

    } // end readNLNM

    private synchronized static void readNHNM(String fileName, NoiseModel noiseModel) {

   // First see if the file exists
        if (!(new File(fileName).exists())) {
            logger.error("NHNM file={} does NOT exist!", fileName);
            return;
        }
   // Temp ArrayList(s) to read in unknown number of (x,y) pairs:
        ArrayList<Double> tmpPers = new ArrayList<Double>();
        ArrayList<Double> tmpPows = new ArrayList<Double>();
        BufferedReader br = null;
        try {
            String line;
            br = new BufferedReader(new FileReader(fileName));
            while ((line = br.readLine()) != null) {
                String[] args = line.trim().split("\\s+") ;
                if (args.length != 2) {
                    String message = "==Error reading NLNM: got " + args.length + " args on one line!";
                    throw new RuntimeException(message);
                }
                tmpPers.add( Double.valueOf(args[0].trim()).doubleValue() );
                tmpPows.add( Double.valueOf(args[1].trim()).doubleValue() );
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (br != null)br.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        Double[] modelPeriods  = tmpPers.toArray(new Double[]{});
        Double[] modelPowers   = tmpPows.toArray(new Double[]{});

        noiseModel.periods = new double[modelPeriods.length];
        noiseModel.powers  = new double[modelPowers.length];

        for (int i=0; i<modelPeriods.length; i++){
            noiseModel.periods[i] = modelPeriods[i];
            noiseModel.powers[i]  = modelPowers[i];
        }

        noiseModel.valid = true;

    } // end readHLNM
    
    public static class NoiseModel
    {
    	double[] periods = null;
    	double[] powers = null;
    	boolean valid = false;
    	
    	public double[] getPeriods()
    	{
    		return periods;
    	}
    	
    	public double[] getPowers()
    	{
    		return powers;
    	}
    	
    	public boolean isValid()
    	{
    		return valid;
    	}
    }

} // end class

