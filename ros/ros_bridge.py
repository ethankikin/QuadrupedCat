#!/usr/bin/env python3
"""Receives depth + pose UDP packets from the Android app (RosBridge.kt) on 127.0.0.1:5600
and publishes them as ROS 2 topics:

  /camera/depth/image_raw    sensor_msgs/Image  16UC1, millimetres, 0 = invalid
  /camera/depth/camera_info  sensor_msgs/CameraInfo
  /odom                      nav_msgs/Odometry  (ARCore pose, only while tracking)
  TF: odom -> base_link (from ARCore), base_link -> camera_depth_optical_frame (static)

ROS body frame: x forward, y left, z up, with base_link at the phone camera.
ARCore frame is x right, y up, z back; P below converts between them.
"""
import socket
import struct

import numpy as np
import rclpy
from geometry_msgs.msg import TransformStamped
from nav_msgs.msg import Odometry
from rclpy.node import Node
from sensor_msgs.msg import CameraInfo, Image
from tf2_ros import StaticTransformBroadcaster, TransformBroadcaster

PORT = 5600
P = np.array([[0, 0, -1], [-1, 0, 0], [0, 1, 0]], dtype=float)  # ARCore -> ROS axes

DEPTH_HDR = struct.Struct('<QHHffff')   # after the 4-byte magic
POSE_HDR = struct.Struct('<Q7fB')


def quat_to_mat(x, y, z, w):
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ])


def mat_to_quat(R):
    t = np.trace(R)
    if t > 0:
        s = 2.0 * np.sqrt(t + 1.0)
        return ((R[2, 1] - R[1, 2]) / s, (R[0, 2] - R[2, 0]) / s, (R[1, 0] - R[0, 1]) / s, 0.25 * s)
    i = int(np.argmax(np.diag(R)))
    j, k = (i + 1) % 3, (i + 2) % 3
    s = 2.0 * np.sqrt(1.0 + R[i, i] - R[j, j] - R[k, k])
    q = [0.0] * 4
    q[i] = 0.25 * s
    q[j] = (R[j, i] + R[i, j]) / s
    q[k] = (R[k, i] + R[i, k]) / s
    w = (R[k, j] - R[j, k]) / s
    return (q[0], q[1], q[2], w)


class Bridge(Node):
    def __init__(self):
        super().__init__('phone_bridge')
        self.img_pub = self.create_publisher(Image, '/camera/depth/image_raw', 5)
        self.info_pub = self.create_publisher(CameraInfo, '/camera/depth/camera_info', 5)
        self.odom_pub = self.create_publisher(Odometry, '/odom', 10)
        self.tf = TransformBroadcaster(self)

        static = StaticTransformBroadcaster(self)
        t = TransformStamped()
        t.header.stamp = self.get_clock().now().to_msg()
        t.header.frame_id = 'base_link'
        t.child_frame_id = 'camera_depth_optical_frame'
        t.transform.rotation.x, t.transform.rotation.y = -0.5, 0.5
        t.transform.rotation.z, t.transform.rotation.w = -0.5, 0.5
        static.sendTransform(t)

        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(('127.0.0.1', PORT))
        self.sock.setblocking(False)
        self.n_depth = 0
        self.n_pose = 0
        self.n_tracking = 0
        self.create_timer(0.005, self.drain)
        self.create_timer(5.0, self.report)
        self.get_logger().info(f'listening on udp://127.0.0.1:{PORT}')

    def report(self):
        self.get_logger().info(
            f'last 5 s: {self.n_depth} depth, {self.n_pose} pose ({self.n_tracking} tracking) packets')
        self.n_depth = self.n_pose = self.n_tracking = 0

    def drain(self):
        while True:
            try:
                data = self.sock.recv(65535)
            except BlockingIOError:
                return
            magic = data[:4]
            if magic == b'QDEP':
                self.n_depth += 1
                self.on_depth(data)
            elif magic == b'QPOS':
                self.n_pose += 1
                self.on_pose(data)

    def on_depth(self, data):
        _, w, h, fx, fy, cx, cy = DEPTH_HDR.unpack_from(data, 4)
        raw = np.frombuffer(data, dtype='<u2', offset=4 + DEPTH_HDR.size, count=w * h).reshape(h, w)
        conf = raw >> 13
        # DEPTH16 confidence: 0 = 100%, 1 = 0%, n>=2 = (n-1)/7. Keep >= ~30% (same as the app).
        valid = (conf == 0) | (conf >= 4)
        mm = np.where(valid, raw & 0x1FFF, 0).astype('<u2')

        stamp = self.get_clock().now().to_msg()
        img = Image()
        img.header.stamp = stamp
        img.header.frame_id = 'camera_depth_optical_frame'
        img.height, img.width = h, w
        img.encoding = '16UC1'
        img.is_bigendian = 0
        img.step = w * 2
        img.data = mm.tobytes()
        self.img_pub.publish(img)

        info = CameraInfo()
        info.header = img.header
        info.height, info.width = h, w
        info.distortion_model = 'plumb_bob'
        info.d = [0.0] * 5
        info.k = [fx, 0.0, cx, 0.0, fy, cy, 0.0, 0.0, 1.0]
        info.r = [1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]
        info.p = [fx, 0.0, cx, 0.0, 0.0, fy, cy, 0.0, 0.0, 0.0, 1.0, 0.0]
        self.info_pub.publish(info)

    def on_pose(self, data):
        _, tx, ty, tz, qx, qy, qz, qw, tracking = POSE_HDR.unpack_from(data, 4)
        if not tracking:
            return
        self.n_tracking += 1
        R = P @ quat_to_mat(qx, qy, qz, qw) @ P.T
        t = P @ np.array([tx, ty, tz])
        q = mat_to_quat(R)

        stamp = self.get_clock().now().to_msg()
        tf = TransformStamped()
        tf.header.stamp = stamp
        tf.header.frame_id = 'odom'
        tf.child_frame_id = 'base_link'
        tf.transform.translation.x, tf.transform.translation.y, tf.transform.translation.z = map(float, t)
        tf.transform.rotation.x, tf.transform.rotation.y = float(q[0]), float(q[1])
        tf.transform.rotation.z, tf.transform.rotation.w = float(q[2]), float(q[3])
        self.tf.sendTransform(tf)

        od = Odometry()
        od.header = tf.header
        od.child_frame_id = 'base_link'
        od.pose.pose.position.x, od.pose.pose.position.y, od.pose.pose.position.z = map(float, t)
        od.pose.pose.orientation = tf.transform.rotation
        self.odom_pub.publish(od)


def main():
    rclpy.init()
    node = Bridge()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    node.destroy_node()
    rclpy.shutdown()


if __name__ == '__main__':
    main()
