package com.chameleonvision.vision.pipeline;

import com.chameleonvision.vision.camera.CameraCapture;
import com.chameleonvision.vision.camera.CaptureStaticProperties;
import com.chameleonvision.vision.pipeline.pipes.*;
import com.chameleonvision.vision.enums.ImageRotation;
import org.apache.commons.lang3.tuple.Pair;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.List;

import static com.chameleonvision.vision.pipeline.CVPipeline2d.*;

@SuppressWarnings("WeakerAccess")
public class CVPipeline2d extends CVPipeline<CVPipeline2dResult, CVPipeline2dSettings> {

    private Mat rawCameraMat = new Mat();
    private Mat hsvOutputMat = new Mat();

    private RotateFlipPipe rotateFlipPipe;
    private BlurPipe blurPipe;
    private ErodeDilatePipe erodeDilatePipe;
    private HsvPipe hsvPipe;
    private FindContoursPipe findContoursPipe;
    private FilterContoursPipe filterContoursPipe;
    private SpeckleRejectPipe speckleRejectPipe;
    private GroupContoursPipe groupContoursPipe;
    private SortContoursPipe sortContoursPipe;
    private Collect2dTargetsPipe collect2dTargetsPipe;
    private Draw2dContoursPipe.Draw2dContoursSettings draw2dContoursSettings;
    private Draw2dContoursPipe draw2dContoursPipe;
    private OutputMatPipe outputMatPipe;


    private StringBuilder pipelineTimeStringBuilder = new StringBuilder();
    private CaptureStaticProperties camProps;
    private Scalar hsvLower, hsvUpper;



    public CVPipeline2d() {
        super(new CVPipeline2dSettings());
    }

    public CVPipeline2d(String name) {
        super(name, new CVPipeline2dSettings());
    }

    public CVPipeline2d(CVPipeline2dSettings settings) {
        super(settings);
    }

    @Override
    public void initPipeline(CameraCapture process) {
        super.initPipeline(process);

        camProps = cameraCapture.getProperties().getStaticProperties();
        hsvLower = new Scalar(settings.hue.get(0).intValue(), settings.saturation.get(0).intValue(), settings.value.get(0).intValue());
        hsvUpper = new Scalar(settings.hue.get(1).intValue(), settings.saturation.get(1).intValue(), settings.value.get(1).intValue());

        rotateFlipPipe = new RotateFlipPipe(ImageRotation.DEG_0, settings.flipMode);
        blurPipe = new BlurPipe(5);
        erodeDilatePipe = new ErodeDilatePipe(settings.erode, settings.dilate, 7);
        hsvPipe = new HsvPipe(hsvLower, hsvUpper);
        findContoursPipe = new FindContoursPipe();
        filterContoursPipe = new FilterContoursPipe(settings.area, settings.ratio, settings.extent, camProps);
        speckleRejectPipe = new SpeckleRejectPipe(settings.speckle.doubleValue());
        groupContoursPipe = new GroupContoursPipe(settings.targetGroup, settings.targetIntersection);
        sortContoursPipe = new SortContoursPipe(settings.sortMode, camProps);
        collect2dTargetsPipe = new Collect2dTargetsPipe(settings.calibrationMode, settings.point,
                settings.dualTargetCalibrationM, settings.dualTargetCalibrationB, camProps);
        draw2dContoursSettings = new Draw2dContoursPipe.Draw2dContoursSettings();
        // TODO: make settable from UI? config?
        draw2dContoursSettings.showCentroid = false;
        draw2dContoursSettings.showCrosshair = true;
        draw2dContoursSettings.boxOutlineSize = 2;
        draw2dContoursSettings.showRotatedBox = true;
        draw2dContoursSettings.showMaximumBox = true;
        draw2dContoursPipe = new Draw2dContoursPipe(draw2dContoursSettings, camProps);
        outputMatPipe = new OutputMatPipe(settings.isBinary);
    }

    @Override
    public CVPipeline2dResult runPipeline(Mat inputMat) {
        long totalPipelineTimeNanos = 0;
        long pipelineStartTimeNanos = System.nanoTime();

        if (cameraCapture == null) {
            throw new RuntimeException("Pipeline was not initialized before being run!");
        }
        if(inputMat.cols() <= 1) {
            throw new RuntimeException("Input Mat is empty!");
        }


        pipelineTimeStringBuilder = new StringBuilder();

        inputMat.copyTo(rawCameraMat);

        // prepare pipes
        hsvLower = new Scalar(settings.hue.get(0).intValue(), settings.saturation.get(0).intValue(), settings.value.get(0).intValue());
        hsvUpper = new Scalar(settings.hue.get(1).intValue(), settings.saturation.get(1).intValue(), settings.value.get(1).intValue());
        rotateFlipPipe.setConfig(ImageRotation.DEG_0, settings.flipMode);
        blurPipe.setConfig(0);
        erodeDilatePipe.setConfig(settings.erode, settings.dilate, 7);
        hsvPipe.setConfig(hsvLower, hsvUpper);
        filterContoursPipe.setConfig(settings.area, settings.ratio, settings.extent, camProps);
        speckleRejectPipe.setConfig(settings.speckle.doubleValue());
        groupContoursPipe.setConfig(settings.targetGroup, settings.targetIntersection);
        sortContoursPipe.setConfig(settings.sortMode, camProps);
        collect2dTargetsPipe.setConfig(settings.calibrationMode, settings.point,
                settings.dualTargetCalibrationM, settings.dualTargetCalibrationB, camProps);
        draw2dContoursPipe.setConfig(camProps);
        outputMatPipe.setConfig(settings.isBinary);

        long pipeInitTimeNanos = System.nanoTime() - pipelineStartTimeNanos;

        // run pipes
        Pair<Mat, Long> rotateFlipResult = rotateFlipPipe.run(inputMat);
        totalPipelineTimeNanos += rotateFlipResult.getRight();

        Pair<Mat, Long> blurResult = blurPipe.run(rotateFlipResult.getLeft());
        totalPipelineTimeNanos += blurResult.getRight();

        Pair<Mat, Long> erodeDilateResult = erodeDilatePipe.run(blurResult.getLeft());
        totalPipelineTimeNanos += erodeDilateResult.getRight();

        Pair<Mat, Long> hsvResult = hsvPipe.run(erodeDilateResult.getLeft());
        totalPipelineTimeNanos += hsvResult.getRight();

        // Todo: move to a pipe
        try {
            Imgproc.cvtColor(hsvResult.getLeft(), hsvOutputMat, Imgproc.COLOR_GRAY2BGR, 3);
        } catch (CvException e) {
            System.err.println("(CVPipeline2d) Exception thrown by OpenCV: \n" + e.getMessage());
        }

        Pair<List<MatOfPoint>, Long> findContoursResult = findContoursPipe.run(hsvResult.getLeft());
        totalPipelineTimeNanos += findContoursResult.getRight();

        Pair<List<MatOfPoint>, Long> filterContoursResult = filterContoursPipe.run(findContoursResult.getLeft());
        totalPipelineTimeNanos += filterContoursResult.getRight();

        Pair<List<MatOfPoint>, Long> speckleRejectResult = speckleRejectPipe.run(filterContoursResult.getLeft());
        totalPipelineTimeNanos += speckleRejectResult.getRight();

        Pair<List<RotatedRect>, Long> groupContoursResult = groupContoursPipe.run(speckleRejectResult.getLeft());
        totalPipelineTimeNanos += groupContoursResult.getRight();

        Pair<List<RotatedRect>, Long> sortContoursResult = sortContoursPipe.run(groupContoursResult.getLeft());
        totalPipelineTimeNanos += sortContoursResult.getRight();

        Pair<List<Target2d>, Long> collect2dTargetsResult = collect2dTargetsPipe.run(sortContoursResult.getLeft());
        totalPipelineTimeNanos += collect2dTargetsResult.getRight();

        // takes pair of (Mat of original camera image, Mat of HSV thresholded image)
        Pair<Mat, Long> outputMatResult = outputMatPipe.run(Pair.of(rawCameraMat, hsvOutputMat));
        totalPipelineTimeNanos += outputMatResult.getRight();

        // takes pair of (Mat to draw on, List<RotatedRect> of sorted contours)
        Pair<Mat, Long> draw2dContoursResult = draw2dContoursPipe.run(Pair.of(outputMatResult.getLeft(), sortContoursResult.getLeft()));
        totalPipelineTimeNanos += draw2dContoursResult.getRight();

        pipelineTimeStringBuilder.append(String.format("PipeInit: %.2fms, ", pipeInitTimeNanos / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("RotateFlip: %.2fms, ", rotateFlipResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("Blur: %.2fms, ", blurResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("ErodeDilate: %.2fms, ", erodeDilateResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("HSV: %.2fms, ", hsvResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("FindContours: %.2fms, ", findContoursResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("FilterContours: %.2fms, ", filterContoursResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("SpeckleReject: %.2fms, ", speckleRejectResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("GroupContours: %.2fms, ", groupContoursResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("SortContours: %.2fms, ", sortContoursResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("Collect2dTargets: %.2fms, ", collect2dTargetsResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("OutputMat: %.2fms, ", outputMatResult.getRight() / 1000000.0));
        pipelineTimeStringBuilder.append(String.format("Draw2dContours: %.2fms, ", draw2dContoursResult.getRight() / 1000000.0));

        System.out.println(pipelineTimeStringBuilder.toString());
        double totalPipelineTimeMillis = totalPipelineTimeNanos / 1000000.0;
        double totalPipelineTimeFPS = 1.0 / (totalPipelineTimeMillis / 1000.0);
        double truePipelineTimeMillis = (System.nanoTime() - pipelineStartTimeNanos) / 1000000.0;
        double truePipelineFPS = 1.0 / (truePipelineTimeMillis / 1000.0);
        System.out.printf("Pipeline processed in %.3fms (%.2fFPS), ", totalPipelineTimeMillis, totalPipelineTimeFPS);
        System.out.printf("full pipeline run time was %.3fms (%.2fFPS)\n", truePipelineTimeMillis, truePipelineFPS);

        return new CVPipeline2dResult(collect2dTargetsResult.getLeft(), draw2dContoursResult.getLeft(), totalPipelineTimeNanos);
    }

    public static class CVPipeline2dResult extends CVPipelineResult<Target2d> {
        public CVPipeline2dResult(List<Target2d> targets, Mat outputMat, long processTimeNanos) {
            super(targets, outputMat, processTimeNanos);
        }
    }

    public static class Target2d {
        public double calibratedX = 0.0;
        public double calibratedY = 0.0;
        public double pitch = 0.0;
        public double yaw = 0.0;
        public double area = 0.0;
        public RotatedRect rawPoint;
    }
}
