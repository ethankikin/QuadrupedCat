// Temporary test: find out whether a DS3240 is the 180 or 270 degree version.
// Moves ONE servo (pin 2) between 500, 1500 and 2500 us, holding each for 3 seconds.
// Measure the horn rotation between the 500 us and 2500 us positions:
//   about 180 deg -> 180 version,  about 270 deg -> 270 version.
// Take the horn off the leg first, and make sure nothing blocks it.

#include <Servo.h>

const uint8_t PIN = 2;
const int PULSES_US[] = {1500, 500, 1500, 2500};
const unsigned long HOLD_MS = 3000;

Servo servo;

void setup() {
  Serial.begin(115200);
  servo.attach(PIN, 500, 2500);          // allow the full 500-2500 us pulse range
  servo.writeMicroseconds(1500);
  delay(1500);
}

void loop() {
  for (uint8_t i = 0; i < sizeof(PULSES_US) / sizeof(PULSES_US[0]); i++) {
    Serial.print("pulse us: ");
    Serial.println(PULSES_US[i]);
    servo.writeMicroseconds(PULSES_US[i]);
    delay(HOLD_MS);
  }
}
