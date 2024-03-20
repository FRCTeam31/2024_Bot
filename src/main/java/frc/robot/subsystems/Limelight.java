// wip

package frc.robot.subsystems;

import edu.wpi.first.math.Matrix;
import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.geometry.Pose3d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.numbers.N1;
import edu.wpi.first.math.numbers.N3;
import edu.wpi.first.math.util.Units;
import edu.wpi.first.networktables.GenericEntry;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj.shuffleboard.BuiltInWidgets;
import edu.wpi.first.wpilibj.shuffleboard.Shuffleboard;
import edu.wpi.first.wpilibj.shuffleboard.ShuffleboardTab;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import prime.physics.LimelightPose;

public class Limelight extends SubsystemBase implements AutoCloseable {

  private NetworkTable m_limelightTable;

  private ShuffleboardTab d_driverTab = Shuffleboard.getTab("Driver");
  private GenericEntry d_tidEntry = d_driverTab
    .add("Targeted APTag", 0)
    .withWidget(BuiltInWidgets.kTextView)
    .withPosition(11, 3)
    .withSize(2, 1)
    .getEntry();
  private GenericEntry d_txEntry = d_driverTab
    .add("Target X Offset", 0)
    .withWidget(BuiltInWidgets.kDial)
    .withProperties(Map.of("Min", -29.8, "Max", 29.8))
    .withPosition(11, 4)
    .withSize(2, 3)
    .getEntry();

  private ExecutorService m_executorService = Executors.newSingleThreadExecutor();

  /**
   * Creates a new Limelight subsystem and sets the camera's pose in the coordinate system of the robot.
   * @param cameraPose
   */
  public Limelight(Pose3d cameraPose) {
    m_limelightTable = NetworkTableInstance.getDefault().getTable("limelight");
    // setCameraPose(cameraPose);
  }

  //#region Basic Targeting Data

  /**
   * Returns Horizontal Offset From Crosshair To Target (LL1: -27 degrees to 27 degrees / LL2: -29.8 to 29.8 degrees)
   */
  public Rotation2d getHorizontalOffsetFromTarget() {
    return Rotation2d.fromDegrees(m_limelightTable.getEntry("tx").getDouble(0.0));
  }

  /**
   * Returns Vertical Offset From Crosshair To Target (LL1: -20.5 degrees to 20.5 degrees / LL2: -24.85 to 24.85 degrees)
   */
  public Rotation2d getVerticalOffsetFromTarget() {
    return Rotation2d.fromDegrees(m_limelightTable.getEntry("ty").getDouble(0.0));
  }

  /**
   * Returns Target Area (0% of image to 100% of image)
   */
  public double getTargetArea() {
    return m_limelightTable.getEntry("ta").getDouble(0.0);
  }

  /**
   * The pipeline's latency contribution (ms). Add to "cl" to get total latency.
   */
  public long getPipelineLatencyMs() {
    return (long) m_limelightTable.getEntry("tl").getDouble(0.0);
  }

  /**
   * Capture pipeline latency (ms). Time between the end of the exposure of the middle row of the sensor to the beginning of the tracking pipeline.
   */
  public long getCapturePipelineLatencyMs() {
    return (long) m_limelightTable.getEntry("cl").getDouble(0.0);
  }

  /**
   * The total latency of the capture and pipeline processing in milliseconds.
   * @return
   */
  public long getTotalLatencyMs() {
    return getPipelineLatencyMs() + getCapturePipelineLatencyMs();
  }

  //#endregion

  //#region AprilTag and 3D Data

  /**
   * ID of the primary in-view AprilTag
   */
  public int getApriltagId() {
    return (int) m_limelightTable.getEntry("tid").getDouble(-1);
  }

  /**
   * Returns the number of AprilTags in the image.
   * @return
   */
  public double getTagCount() {
    // Robot transform in field-space. Translation (X,Y,Z) in meters Rotation(Roll,Pitch,Yaw) in degrees, total latency (cl+tl), tag count, tag span, average tag distance from camera, average tag area (percentage of image)
    var botPose = m_limelightTable.getEntry("botpose").getDoubleArray(new double[11]);

    return botPose[7];
  }

  /**
   * Robot transform in field-space.
   */
  public LimelightPose getRobotPose() {
    var poseData = m_limelightTable.getEntry("botpose").getDoubleArray(new double[11]); // Translation (X,Y,Z) Rotation(Roll,Pitch,Yaw)

    return new LimelightPose(poseData, calculateTrust());
  }

  /**
   * Robot transform in field-space (alliance driverstation WPILIB origin).
   * @param alliance
   */
  public LimelightPose getRobotPose(DriverStation.Alliance alliance) {
    return new LimelightPose(
      alliance == Alliance.Blue
        ? m_limelightTable.getEntry("botpose_wpiblue").getDoubleArray(new double[11])
        : m_limelightTable.getEntry("botpose_wpired").getDoubleArray(new double[11]),
      calculateTrust()
    );
  }

  /**
   * 3D transform of the robot in the coordinate system of the primary in-view AprilTag
   */
  public LimelightPose getRobotPoseInTargetSpace() {
    var poseData = m_limelightTable.getEntry("botpose_targetspace").getDoubleArray(new double[11]); // Translation (X,Y,Z) Rotation(Roll,Pitch,Yaw)

    return new LimelightPose(poseData, calculateTrust());
  }

  /**
   * 3D transform of the camera in the coordinate system of the primary in-view AprilTag
   */
  public LimelightPose getCameraPoseInTargetSpace() {
    var poseData = m_limelightTable.getEntry("camerapose_targetspace").getDoubleArray(new double[11]); // Translation (X,Y,Z) Rotation(Roll,Pitch,Yaw)

    return new LimelightPose(poseData, calculateTrust());
  }

  /**
   * 3D transform of the camera in the coordinate system of the robot
   */
  public LimelightPose getCameraPoseInRobotSpace() {
    var poseData = m_limelightTable.getEntry("camerapose_robotspace").getDoubleArray(new double[11]); // Translation (X,Y,Z) Rotation(Roll,Pitch,Yaw)

    return new LimelightPose(poseData, calculateTrust());
  }

  /**
   * 3D transform of the primary in-view AprilTag in the coordinate system of the Camera
   */
  public LimelightPose getTargetPoseInCameraSpace() {
    var poseData = m_limelightTable.getEntry("targetpose_cameraspace").getDoubleArray(new double[11]); // Translation (X,Y,Z) Rotation(Roll,Pitch,Yaw)

    return new LimelightPose(poseData, calculateTrust());
  }

  /**
   * 3D transform of the primary in-view AprilTag in the coordinate system of the Robot
   */
  public LimelightPose getTargetPoseInRobotSpace() {
    var poseData = m_limelightTable.getEntry("targetpose_robotspace").getDoubleArray(new double[11]); // Translation (X,Y,Z) Rotation(Roll,Pitch,Yaw)

    return new LimelightPose(poseData, calculateTrust());
  }

  /**
   * Calculates a trust value based on the number of tags in view.
   * @return
   */
  public Matrix<N3, N1> calculateTrust() {
    var tagcount = getTagCount();
    return VecBuilder.fill(tagcount >= 2.0 ? 0.5 : 10, tagcount >= 2.0 ? 0.5 : 10, 999999);
  }

  //#endregion

  //#region Camera Controls

  /**
   * Sets limelight’s LED state.
   *    0 = use the LED Mode set in the current pipeline.
   *    1 = force off.
   *    2 = force blink.
   *    3 = force on.
   * @param mode
   */
  public void setLedMode(int mode) {
    m_limelightTable.getEntry("ledMode").setNumber(mode);
  }

  /**
   * Forces the LED to blink a specified number of times, then returns to pipeline control.
   */
  public void blinkLed(int blinkCount) {
    m_executorService.submit(() -> {
      // Blink the LED X times with 100ms on, 200ms off for each blink
      for (int i = 0; i < blinkCount; i++) {
        m_limelightTable.getEntry("ledMode").setNumber(3);

        try {
          Thread.sleep(100);
        } catch (Exception e) {
          Thread.currentThread().interrupt();
        }

        m_limelightTable.getEntry("ledMode").setNumber(1);
        try {
          Thread.sleep(200);
        } catch (Exception e) {
          Thread.currentThread().interrupt();
        }
      }

      // Then return to pipeline control
      setLedMode(0);
    });
  }

  /**
   * Sets limelight’s operation mode.
   *    0 = Vision processor.
   *    1 = Driver Camera (Increases exposure, disables vision processing).
   * @param mode
   */
  public void setCameraMode(int mode) {
    m_limelightTable.getEntry("camMode").setNumber(mode);
  }

  /**
   * Sets limelight’s pipeline.
   * @param pipeline
   */
  public void setPipeline(int pipeline) {
    m_limelightTable.getEntry("pipeline").setNumber(pipeline);
  }

  /**
   * Side-by-side streams (Note: USB output stream is not affected by this mode)
   * @param mode
   */
  public void setPiPStreamingMode(int mode) {
    m_limelightTable.getEntry("stream").setNumber(mode);
  }

  /**
   * Allows users to take snapshots during a match
   * @param mode
   */
  public void takeSnapshot() {
    m_limelightTable.getEntry("snapshot").setNumber(0); // Reset
    m_limelightTable.getEntry("snapshot").setNumber(1); // Take snapshot
    m_limelightTable.getEntry("snapshot").setNumber(0); // Reset
  }

  /**
   * Set the camera's pose in the coordinate system of the robot.
   * @param pose
   */
  public void setCameraPose(Pose3d pose) {
    var poseData = new double[] {
      pose.getTranslation().getX(),
      pose.getTranslation().getY(),
      pose.getTranslation().getZ(),
      Units.radiansToDegrees(pose.getRotation().getX()),
      Units.radiansToDegrees(pose.getRotation().getY()),
      Units.radiansToDegrees(pose.getRotation().getZ()),
    };

    m_limelightTable.getEntry("camerapose_robotspace_set").setDoubleArray(poseData);
  }

  //#endregion

  public void periodic() {
    d_tidEntry.setDouble(getApriltagId());
    d_txEntry.setDouble(getHorizontalOffsetFromTarget().getDegrees());

    // Level2 logging
    SmartDashboard.putNumber("Limelight/PrimaryTargetID", getApriltagId());
    SmartDashboard.putNumber("Limelight/HorizontalOffset", getHorizontalOffsetFromTarget().getDegrees());
  }

  public boolean isSpeakerCenterTarget(int apriltagId) {
    return apriltagId == 4 || apriltagId == 7;
  }

  public boolean isValidApriltag(int apriltagId) {
    return apriltagId >= 1 && apriltagId <= 16;
  }

  public void close() {
    m_executorService.shutdown();
  }
}
