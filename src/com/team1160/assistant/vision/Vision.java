package com.team1160.assistant.vision;

import com.team1160.assistant.RobotMap;
import edu.wpi.first.wpilibj.camera.AxisCamera;
import edu.wpi.first.wpilibj.camera.AxisCameraException;
import edu.wpi.first.wpilibj.image.BinaryImage;
import edu.wpi.first.wpilibj.image.ColorImage;
import edu.wpi.first.wpilibj.image.CriteriaCollection;
import edu.wpi.first.wpilibj.image.NIVision;
import edu.wpi.first.wpilibj.image.NIVisionException;
import edu.wpi.first.wpilibj.image.ParticleAnalysisReport;

/*
 * -insert long warning here regarding this class-
 * 
 * 
 * ..you have been warned.
 */
public class Vision {

    AxisCamera camera;
    CriteriaCollection cc;
    public boolean autonomous;

    public class Scores {

        double rectangularity;
        double aspectRatioVertical;
        double aspectRatioHorizontal;
    }

    public class TargetReport {

        int verticalIndex;
        int horizontalIndex;
        boolean Hot;
        double totalScore;
        double leftScore;
        double rightScore;
        double tapeWidthScore;
        double verticalScore;
    };

    public void robotInit() {
        camera = AxisCamera.getInstance();  // get an instance of the camera
        cc = new CriteriaCollection();      // create the criteria for the particle filter
        cc.addCriteria(NIVision.MeasurementType.IMAQ_MT_AREA, RobotMap.AREA_MINIMUM, 65535, false);
    }

    double computeDistance(BinaryImage image, ParticleAnalysisReport report, int particleNumber) throws NIVisionException {
        double rectLong, height;
        int targetHeight;

        rectLong = NIVision.MeasureParticle(image.image, particleNumber, false, NIVision.MeasurementType.IMAQ_MT_EQUIVALENT_RECT_LONG_SIDE);
        //using the smaller of the estimated rectangle long side and the bounding rectangle height results in better performance
        //on skewed rectangles
        height = Math.min(report.boundingRectHeight, rectLong);
        targetHeight = 32;

        return RobotMap.Y_IMAGE_RES * targetHeight / (height * 12 * 2 * Math.tan(RobotMap.VIEW_ANGLE * Math.PI / (180 * 2)));
    }

    /**
     * Computes a score (0-100) comparing the aspect ratio to the ideal aspect
     * ratio for the target. This method uses the equivalent rectangle sides to
     * determine aspect ratio as it performs better as the target gets skewed by
     * moving to the left or right. The equivalent rectangle is the rectangle
     * with sides x and y where particle area= x*y and particle perimeter= 2x+2y
     *
     * @param image The image containing the particle to score, needed to
     * perform additional measurements
     * @param report The Particle Analysis Report for the particle, used for the
     * width, height, and particle number
     * @param outer	Indicates whether the particle aspect ratio should be
     * compared to the ratio for the inner target or the outer
     * @return The aspect ratio score (0-100)
     */
    public double scoreAspectRatio(BinaryImage image, ParticleAnalysisReport report, int particleNumber, boolean vertical) throws NIVisionException {
        double rectLong, rectShort, aspectRatio, idealAspectRatio;

        rectLong = NIVision.MeasureParticle(image.image, particleNumber, false, NIVision.MeasurementType.IMAQ_MT_EQUIVALENT_RECT_LONG_SIDE);
        rectShort = NIVision.MeasureParticle(image.image, particleNumber, false, NIVision.MeasurementType.IMAQ_MT_EQUIVALENT_RECT_SHORT_SIDE);
        idealAspectRatio = vertical ? (4.0 / 32) : (23.5 / 4);	//Vertical reflector 4" wide x 32" tall, horizontal 23.5" wide x 4" tall

        //Divide width by height to measure aspect ratio
        if (report.boundingRectWidth > report.boundingRectHeight) {
            //particle is wider than it is tall, divide long by short
            aspectRatio = ratioToScore((rectLong / rectShort) / idealAspectRatio);
        } else {
            //particle is taller than it is wide, divide short by long
            aspectRatio = ratioToScore((rectShort / rectLong) / idealAspectRatio);
        }
        return aspectRatio;
    }

    /**
     * Compares scores to defined limits and returns true if the particle
     * appears to be a target
     *
     * @param scores The structure containing the scores to compare
     * @param outer True if the particle should be treated as an outer target,
     * false to treat it as a center target
     *
     * @return True if the particle meets all limits, false otherwise
     */
    boolean scoreCompare(Scores scores, boolean vertical) {
        boolean isTarget = true;

        isTarget &= scores.rectangularity > RobotMap.RECTANGULARITY_LIMIT;
        if (vertical) {
            isTarget &= scores.aspectRatioVertical > RobotMap.ASPECT_RATIO_LIMIT;
        } else {
            isTarget &= scores.aspectRatioHorizontal > RobotMap.ASPECT_RATIO_LIMIT;
        }

        return isTarget;
    }

    /**
     * Computes a score (0-100) estimating how rectangular the particle is by
     * comparing the area of the particle to the area of the bounding box
     * surrounding it. A perfect rectangle would cover the entire bounding box.
     *
     * @param report The Particle Analysis Report for the particle to score
     * @return The rectangularity score (0-100)
     */
    double scoreRectangularity(ParticleAnalysisReport report) {
        if (report.boundingRectWidth * report.boundingRectHeight != 0) {
            return 100 * report.particleArea / (report.boundingRectWidth * report.boundingRectHeight);
        } else {
            return 0;
        }
    }

    /**
     * Converts a ratio with ideal value of 1 to a score. The resulting function
     * is piecewise linear going from (0,0) to (1,100) to (2,0) and is 0 for all
     * inputs outside the range 0-2
     */
    double ratioToScore(double ratio) {
        return (Math.max(0, Math.min(100 * (1 - Math.abs(1 - ratio)), 100)));
    }

    /**
     * Takes in a report on a target and compares the scores to the defined
     * score limits to evaluate if the target is a hot target or not.
     *
     * Returns True if the target is hot. False if it is not.
     */
    boolean hotOrNot(TargetReport target) {
        boolean isHot = true;

        isHot &= target.tapeWidthScore >= RobotMap.TAPE_WIDTH_LIMIT;
        isHot &= target.verticalScore >= RobotMap.VERTICAL_SCORE_LIMIT;
        isHot &= (target.leftScore > RobotMap.LR_SCORE_LIMIT) | (target.rightScore > RobotMap.LR_SCORE_LIMIT);

        return isHot;
    }

    public void vision(boolean auto) {
        TargetReport target = new TargetReport();
        int verticalTargets[] = new int[RobotMap.MAX_PARTICLES];
        int horizontalTargets[] = new int[RobotMap.MAX_PARTICLES];
        int verticalTargetCount, horizontalTargetCount;

        while (auto) {
            try {

                ColorImage image = null;

                image = camera.getImage();

                BinaryImage thresholdImage = image.thresholdHSV(136, 182, 45, 255, 116, 255);
                thresholdImage.write("/threshold.bmp");
                BinaryImage filteredImage = thresholdImage.particleFilter(cc);
                filteredImage.write("/filteredImage.bmp");

                //iterate through each particle and score to see if it is a target
                Scores scores[] = new Scores[filteredImage.getNumberParticles()];
                horizontalTargetCount = verticalTargetCount = 0;

                if (filteredImage.getNumberParticles() > 0) {
                    for (int i = 0; i < RobotMap.MAX_PARTICLES && i < filteredImage.getNumberParticles(); i++) {
                        ParticleAnalysisReport report = filteredImage.getParticleAnalysisReport(i);
                        scores[i] = new Scores();

                        //Score each particle on rectangularity and aspect ratio
                        scores[i].rectangularity = scoreRectangularity(report);
                        scores[i].aspectRatioVertical = scoreAspectRatio(filteredImage, report, i, true);
                        scores[i].aspectRatioHorizontal = scoreAspectRatio(filteredImage, report, i, false);

                        //Check if the particle is a horizontal target, if not, check if it's a vertical target
                        if (scoreCompare(scores[i], false)) {
                            System.out.println("particle: " + i + "is a Horizontal Target centerX: " + report.center_mass_x + "centerY: " + report.center_mass_y);
                            horizontalTargets[horizontalTargetCount++] = i; //Add particle to target array and increment count
                        } else if (scoreCompare(scores[i], true)) {
                            System.out.println("particle: " + i + "is a Vertical Target centerX: " + report.center_mass_x + "centerY: " + report.center_mass_y);
                            verticalTargets[verticalTargetCount++] = i;  //Add particle to target array and increment count
                        } else {
                            System.out.println("particle: " + i + "is not a Target centerX: " + report.center_mass_x + "centerY: " + report.center_mass_y);
                        }
                        System.out.println("rect: " + scores[i].rectangularity + "ARHoriz: " + scores[i].aspectRatioHorizontal);
                        System.out.println("ARVert: " + scores[i].aspectRatioVertical);
                    }

                    //Zero out scores and set verticalIndex to first target in case there are no horizontal targets
                    target.totalScore = target.leftScore = target.rightScore = target.tapeWidthScore = target.verticalScore = 0;
                    target.verticalIndex = verticalTargets[0];
                    for (int i = 0; i < verticalTargetCount; i++) {
                        ParticleAnalysisReport verticalReport = filteredImage.getParticleAnalysisReport(verticalTargets[i]);
                        for (int j = 0; j < horizontalTargetCount; j++) {
                            ParticleAnalysisReport horizontalReport = filteredImage.getParticleAnalysisReport(horizontalTargets[j]);
                            double horizWidth, horizHeight, vertWidth, leftScore, rightScore, tapeWidthScore, verticalScore, total;

                            //Measure equivalent rectangle sides for use in score calculation
                            horizWidth = NIVision.MeasureParticle(filteredImage.image, horizontalTargets[j], false, NIVision.MeasurementType.IMAQ_MT_EQUIVALENT_RECT_LONG_SIDE);
                            vertWidth = NIVision.MeasureParticle(filteredImage.image, verticalTargets[i], false, NIVision.MeasurementType.IMAQ_MT_EQUIVALENT_RECT_SHORT_SIDE);
                            horizHeight = NIVision.MeasureParticle(filteredImage.image, horizontalTargets[j], false, NIVision.MeasurementType.IMAQ_MT_EQUIVALENT_RECT_SHORT_SIDE);

                            //Determine if the horizontal target is in the expected location to the left of the vertical target
                            leftScore = ratioToScore(1.2 * (verticalReport.boundingRectLeft - horizontalReport.center_mass_x) / horizWidth);
                            //Determine if the horizontal target is in the expected location to the right of the  vertical target
                            rightScore = ratioToScore(1.2 * (horizontalReport.center_mass_x - verticalReport.boundingRectLeft - verticalReport.boundingRectWidth) / horizWidth);
                            //Determine if the width of the tape on the two targets appears to be the same
                            tapeWidthScore = ratioToScore(vertWidth / horizHeight);
                            //Determine if the vertical location of the horizontal target appears to be correct
                            verticalScore = ratioToScore(1 - (verticalReport.boundingRectTop - horizontalReport.center_mass_y) / (4 * horizHeight));
                            total = leftScore > rightScore ? leftScore : rightScore;
                            total += tapeWidthScore + verticalScore;

                            //If the target is the best detected so far store the information about it
                            if (total > target.totalScore) {
                                target.horizontalIndex = horizontalTargets[j];
                                target.verticalIndex = verticalTargets[i];
                                target.totalScore = total;
                                target.leftScore = leftScore;
                                target.rightScore = rightScore;
                                target.tapeWidthScore = tapeWidthScore;
                                target.verticalScore = verticalScore;
                            }
                        }
                        //Determine if the best target is a Hot target
                        target.Hot = hotOrNot(target);
                    }

                    if (verticalTargetCount > 0) {
                        //Information about the target is contained in the "target" structure
                        //To get measurement information such as sizes or locations use the
                        //horizontal or vertical index to get the particle report as shown below
                        ParticleAnalysisReport distanceReport = filteredImage.getParticleAnalysisReport(target.verticalIndex);
                        double distance = computeDistance(filteredImage, distanceReport, target.verticalIndex);
                        if (target.Hot) {
                            System.out.println("Hot target located");
                            System.out.println("Distance: " + distance);
                        } else {
                            System.out.println("No hot target present");
                            System.out.println("Distance: " + distance);
                        }
                    }
                }

                /**
                 * all images in Java must be freed after they are used since
                 * they are allocated out of C data structures. Not calling
                 * free() will cause the memory to accumulate over each pass of
                 * this loop.
                 */
                filteredImage.free();
                thresholdImage.free();
                image.free();

            } catch (AxisCameraException ex) {        // this is needed if the camera.getImage() is called
                ex.printStackTrace();
            } catch (NIVisionException ex) {
                ex.printStackTrace();
            }
        }
    }

    public void visionTel() {
        TargetReport target = new TargetReport();
        int verticalTargets[] = new int[RobotMap.MAX_PARTICLES];
        int horizontalTargets[] = new int[RobotMap.MAX_PARTICLES];
        int verticalTargetCount, horizontalTargetCount;

        try {
            ColorImage image = null;

            image = camera.getImage();

            BinaryImage thresholdImage = image.thresholdHSV(136, 182, 45, 255, 116, 255);
            thresholdImage.write("/threshold.bmp");
            BinaryImage filteredImage = thresholdImage.particleFilter(cc);
            filteredImage.write("/filteredImage.bmp");

            //iterate through each particle and score to see if it is a target
            Scores scores[] = new Scores[filteredImage.getNumberParticles()];
            horizontalTargetCount = verticalTargetCount = 0;

            if (filteredImage.getNumberParticles() > 0) {
                for (int i = 0; i < RobotMap.MAX_PARTICLES && i < filteredImage.getNumberParticles(); i++) {
                    ParticleAnalysisReport report = filteredImage.getParticleAnalysisReport(i);
                    scores[i] = new Scores();

                    //Score each particle on rectangularity and aspect ratio
                    scores[i].rectangularity = scoreRectangularity(report);
                    scores[i].aspectRatioVertical = scoreAspectRatio(filteredImage, report, i, true);
                    scores[i].aspectRatioHorizontal = scoreAspectRatio(filteredImage, report, i, false);

                    //Check if the particle is a horizontal target, if not, check if it's a vertical target
                    if (scoreCompare(scores[i], false)) {
                        System.out.println("particle: " + i + "is a Horizontal Target centerX: " + report.center_mass_x + "centerY: " + report.center_mass_y);
                        horizontalTargets[horizontalTargetCount++] = i; //Add particle to target array and increment count
                    } else if (scoreCompare(scores[i], true)) {
                        System.out.println("particle: " + i + "is a Vertical Target centerX: " + report.center_mass_x + "centerY: " + report.center_mass_y);
                        verticalTargets[verticalTargetCount++] = i;  //Add particle to target array and increment count
                    } else {
                        System.out.println("particle: " + i + "is not a Target centerX: " + report.center_mass_x + "centerY: " + report.center_mass_y);
                    }
                    System.out.println("rect: " + scores[i].rectangularity + "ARHoriz: " + scores[i].aspectRatioHorizontal);
                    System.out.println("ARVert: " + scores[i].aspectRatioVertical);
                }

                //Zero out scores and set verticalIndex to first target in case there are no horizontal targets
                target.totalScore = target.leftScore = target.rightScore = target.tapeWidthScore = target.verticalScore = 0;
                target.verticalIndex = verticalTargets[0];
                for (int i = 0; i < verticalTargetCount; i++) {
                    ParticleAnalysisReport verticalReport = filteredImage.getParticleAnalysisReport(verticalTargets[i]);
                    for (int j = 0; j < horizontalTargetCount; j++) {
                        ParticleAnalysisReport horizontalReport = filteredImage.getParticleAnalysisReport(horizontalTargets[j]);
                        double horizWidth, horizHeight, vertWidth, leftScore, rightScore, tapeWidthScore, verticalScore, total;

                        //Measure equivalent rectangle sides for use in score calculation
                        horizWidth = NIVision.MeasureParticle(filteredImage.image, horizontalTargets[j], false, NIVision.MeasurementType.IMAQ_MT_EQUIVALENT_RECT_LONG_SIDE);
                        vertWidth = NIVision.MeasureParticle(filteredImage.image, verticalTargets[i], false, NIVision.MeasurementType.IMAQ_MT_EQUIVALENT_RECT_SHORT_SIDE);
                        horizHeight = NIVision.MeasureParticle(filteredImage.image, horizontalTargets[j], false, NIVision.MeasurementType.IMAQ_MT_EQUIVALENT_RECT_SHORT_SIDE);

                        //Determine if the horizontal target is in the expected location to the left of the vertical target
                        leftScore = ratioToScore(1.2 * (verticalReport.boundingRectLeft - horizontalReport.center_mass_x) / horizWidth);
                        //Determine if the horizontal target is in the expected location to the right of the  vertical target
                        rightScore = ratioToScore(1.2 * (horizontalReport.center_mass_x - verticalReport.boundingRectLeft - verticalReport.boundingRectWidth) / horizWidth);
                        //Determine if the width of the tape on the two targets appears to be the same
                        tapeWidthScore = ratioToScore(vertWidth / horizHeight);
                        //Determine if the vertical location of the horizontal target appears to be correct
                        verticalScore = ratioToScore(1 - (verticalReport.boundingRectTop - horizontalReport.center_mass_y) / (4 * horizHeight));
                        total = leftScore > rightScore ? leftScore : rightScore;
                        total += tapeWidthScore + verticalScore;

                        //If the target is the best detected so far store the information about it
                        if (total > target.totalScore) {
                            target.horizontalIndex = horizontalTargets[j];
                            target.verticalIndex = verticalTargets[i];
                            target.totalScore = total;
                            target.leftScore = leftScore;
                            target.rightScore = rightScore;
                            target.tapeWidthScore = tapeWidthScore;
                            target.verticalScore = verticalScore;
                        }
                    }
                    //Determine if the best target is a Hot target
                    target.Hot = hotOrNot(target);
                }

                if (verticalTargetCount > 0) {
                    //Information about the target is contained in the "target" structure
                    //To get measurement information such as sizes or locations use the
                    //horizontal or vertical index to get the particle report as shown below
                    ParticleAnalysisReport distanceReport = filteredImage.getParticleAnalysisReport(target.verticalIndex);
                    double distance = computeDistance(filteredImage, distanceReport, target.verticalIndex);
                    if (target.Hot) {
                        System.out.println("Hot target located");
                        System.out.println("Distance: " + distance);
                    } else {
                        System.out.println("No hot target present");
                        System.out.println("Distance: " + distance);
                    }
                }
            }

            /**
             * all images in Java must be freed after they are used since they
             * are allocated out of C data structures. Not calling free() will
             * cause the memory to accumulate over each pass of this loop.
             */
            filteredImage.free();
            thresholdImage.free();
            image.free();

        } catch (AxisCameraException ex) {        // this is needed if the camera.getImage() is called
            ex.printStackTrace();
        } catch (NIVisionException ex) {
            ex.printStackTrace();
        }
    }
}
