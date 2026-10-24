#!/bin/bash
# Run INSIDE the proot Ubuntu, after setup_ros.sh. Start the Android app first.
source /root/.ros_env
exec python3 /root/quadruped_ros/ros_bridge.py
