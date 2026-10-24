# ROS 2 on the phone (Termux + proot Ubuntu)

The app streams depth and pose over UDP to 127.0.0.1:5600. `ros_bridge.py` runs in a
proot Ubuntu inside Termux and publishes them as ROS 2 topics. Nothing leaves the phone.

## One-time setup

1. Install **Termux from F-Droid or its GitHub releases** (the Play Store build is outdated and broken).
2. From the PC, copy this folder to the phone:
   `adb push ros /sdcard/Download/quadruped_ros`
3. In Termux:
   ```
   termux-setup-storage            # accept the permission prompt
   pkg update -y && pkg install -y proot-distro
   proot-distro install ubuntu
   proot-distro login ubuntu --bind /sdcard/Download/quadruped_ros:/root/quadruped_ros -- bash /root/quadruped_ros/setup_ros.sh
   ```
   This downloads about 2-3 GB and takes a while.
4. Android 12+ kills Termux child processes; disable that once from the PC:
   `adb shell device_config set_sync_disabled_for_tests persistent`
   `adb shell device_config put activity_manager max_phantom_processes 2147483647`

## Each run

1. Start the Quadruped Cat app and point it at something while moving the phone.
2. In Termux:
   `proot-distro login ubuntu --bind /sdcard/Download/quadruped_ros:/root/quadruped_ros -- bash /root/quadruped_ros/run_bridge.sh`
3. Check topics from a second Termux session (swipe in from the left edge, New session):
   `proot-distro login ubuntu -- bash -c 'source /root/.ros_env; ros2 topic hz /camera/depth/image_raw'`

## Topics

| Topic / TF | Type |
|---|---|
| `/camera/depth/image_raw` | `sensor_msgs/Image`, 16UC1 in mm, 0 = invalid |
| `/camera/depth/camera_info` | `sensor_msgs/CameraInfo` |
| `/odom` | `nav_msgs/Odometry` (only while ARCore is tracking) |
| TF `odom -> base_link` | ARCore pose, converted to ROS axes (x fwd, y left, z up) |
| TF `base_link -> camera_depth_optical_frame` | static |
