package de.ovgu.skunk.detection.data;

import de.ovgu.skunk.detection.detector.DetectionConfig;
import de.ovgu.skunk.detection.output.ProcessedDataHandler;

/**
 * Created by wfenske on 08.12.16.
 */
public class Context {

    public final DetectionConfig config;
    public final FileCollection files;
    public final MethodCollection functions;
    public final FeatureExpressionCollection featureExpressions;
    public final ProcessedDataHandler processedDataHandler;

    public Context(DetectionConfig config) {
        this.config = config;
        this.files = new FileCollection(this);
        this.functions = new MethodCollection();
        this.featureExpressions = new FeatureExpressionCollection(this);
        this.processedDataHandler = new ProcessedDataHandler(this);
    }


    public String getMetricsOutputFilenamePrefix() {
        return getGeneralOutputFilenamePrefix() + "metrics_";
    }

//    private String currentDateString = null;
//    public synchronized String getGeneralOutputFilenamePrefix() {
//        if (this.currentDateString == null) currentDateString = new SimpleDateFormat("yyyyMMddHHmm").format(new Date());
//        return this.currentDateString;
//    }

    public String getGeneralOutputFilenamePrefix() {
        return "skunk_";
    }

    public String getDetectionOutputFilenamePrefix() {
        return getGeneralOutputFilenamePrefix() + "detection_";
    }

    public String getProcessedDataFilenamePrefix() {
        return getGeneralOutputFilenamePrefix() + "intermediate_";
    }
}