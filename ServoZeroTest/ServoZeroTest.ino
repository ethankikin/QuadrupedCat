// Temporary test: drive the servos on pins 2-9 to 0 degrees and hold them there.
// Separate from QuadrupedFirmware.ino - flash that sketch again when you are done testing.

#include <Servo.h>

const uint8_t FIRST_PIN = 2;
const uint8_t LAST_PIN = 9;
const uint8_t NUM_SERVOS = LAST_PIN - FIRST_PIN + 1;
const int TEST_ANGLE = 0;

Servo servos[NUM_SERVOS];

void setup() {
  for (uint8_t i = 0; i < NUM_SERVOS; i++) {
    servos[i].write(TEST_ANGLE);          // set the target before attaching
    servos[i].attach(FIRST_PIN + i);
  }
}

void loop() {}
