#!/bin/bash
# Run INSIDE the proot Ubuntu. Installs ROS 2 (ros-base only, no GUI tools) and numpy.
set -e

. /etc/os-release
case "$VERSION_ID" in
  22.04) DISTRO=humble ;;
  24.04) DISTRO=jazzy ;;
  26.04) DISTRO=lyrical ;;
  *) echo "Ubuntu $VERSION_ID has no ROS 2 distro mapped in this script. Stopping."; exit 1 ;;
esac
echo "Ubuntu $VERSION_ID -> ROS 2 $DISTRO"

apt update
apt install -y curl software-properties-common
add-apt-repository -y universe

VER=$(curl -s https://api.github.com/repos/ros-infrastructure/ros-apt-source/releases/latest | grep -F tag_name | awk -F\" '{print $4}')
curl -fL -o /tmp/ros2-apt-source.deb \
  "https://github.com/ros-infrastructure/ros-apt-source/releases/download/${VER}/ros2-apt-source_${VER}.${VERSION_CODENAME}_all.deb" \
  || { echo "No ROS apt source for Ubuntu ${VERSION_CODENAME}. Stopping."; exit 1; }
apt install -y /tmp/ros2-apt-source.deb

apt update
if ! apt-cache show "ros-${DISTRO}-ros-base" > /dev/null 2>&1; then
  echo "ros-${DISTRO}-ros-base is not available for this Ubuntu/architecture. Stopping."
  exit 1
fi
apt install -y "ros-${DISTRO}-ros-base" python3-numpy

echo "export ROS_DISTRO=${DISTRO}" > /root/.ros_env
echo "source /opt/ros/${DISTRO}/setup.bash" >> /root/.ros_env
echo "Done. Run: bash /root/quadruped_ros/run_bridge.sh"
