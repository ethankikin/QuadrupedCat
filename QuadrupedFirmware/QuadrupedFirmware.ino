// Quadruped servo driver for Arduino Mega (8 servos wired directly: hip + knee per leg).
//
// The phone computes the gait, inverse kinematics and balance; this sketch only moves the
// servos to the positions it is given.
//
// Serial protocol (115200 baud, ASCII, newline-terminated), phone -> Arduino:
//   A,<a0>,...,<a7>   target angles in degrees (8 values)
//   U,<u0>,...,<u7>   target pulse widths in microseconds (8 values)
//   P                 ping; replies "OK"
// Order for both: hip, knee for each of FL, FR, RL, RR (index = leg * 2 + joint).
// Servos are attached on the first valid A or U line (jumping straight to that position), so
// nothing moves at power-up. After that each servo is slew-rate limited. If nothing arrives
// the servos simply hold their last target.
//
// The servos are 270 degree DS3240s limited to a centred 180 degree window (see LIMIT_MIN_US /
// LIMIT_MAX_US below); angles 0..180 and pulses are both confined to it.
//
// Debug output, Arduino -> phone:
//   STAT att=<0|1> mode=<A|U|-> ok=<valid lines> bad=<rejected lines> us=<8 target pulses>   once a second
//   BAD <start of the rejected line>                                                         on a rejected line
// The built-in LED (pin 13) toggles on every valid A or U line.

#include <Servo.h>

const uint8_t NUM_SERVOS = 8;

// Index = leg * 2 + joint. FL hip/knee, FR hip/knee, RL hip/knee, RR hip/knee.
const uint8_t SERVO_PINS[NUM_SERVOS] = {2, 3, 4, 5, 6, 7, 8, 9};

// Full pulse range of the servo (DS3240 rating); the range the Servo library is told about.
const int PULSE_MIN_US = 500;
const int PULSE_MAX_US = 2500;

// The servos are the 270 degree version, but only a 180 degree window centred in that
// travel is used: angle 0..180 maps to LIMIT_MIN_US..LIMIT_MAX_US (about 833..2167 us),
// so 90 degrees is the middle of travel (1500 us). U lines are clamped to the same window.
const float SERVO_RANGE_DEG = 270.0f;    // full travel across PULSE_MIN_US..PULSE_MAX_US
const float USABLE_RANGE_DEG = 180.0f;   // travel actually allowed
const float LIMIT_MIN_US = PULSE_MIN_US +
    (SERVO_RANGE_DEG - USABLE_RANGE_DEG) / 2.0f / SERVO_RANGE_DEG * (PULSE_MAX_US - PULSE_MIN_US);
const float LIMIT_MAX_US = PULSE_MAX_US -
    (SERVO_RANGE_DEG - USABLE_RANGE_DEG) / 2.0f / SERVO_RANGE_DEG * (PULSE_MAX_US - PULSE_MIN_US);

// Safety limits for A lines, degrees. Tighten these to what the mechanics allow.
const int MIN_ANGLE[NUM_SERVOS] = {10, 10, 10, 10, 10, 10, 10, 10};
const int MAX_ANGLE[NUM_SERVOS] = {170, 170, 170, 170, 170, 170, 170, 170};

const float MAX_DEG_PER_S = 360.0f;   // slew limit
const unsigned long UPDATE_MS = 10;
const unsigned long STAT_MS = 1000;

Servo servos[NUM_SERVOS];
float targetUs[NUM_SERVOS];
float currentUs[NUM_SERVOS];
bool attached = false;
char lastMode = '-';
unsigned long lastUpdateMs = 0;
unsigned long lastStatMs = 0;

unsigned long okLines = 0;
unsigned long badLines = 0;
bool badPending = false;
char badSample[32];

char lineBuf[96];
uint8_t lineLen = 0;

static float clampf(float v, float lo, float hi) {
  return v < lo ? lo : (v > hi ? hi : v);
}

static float angleToUs(float deg) {
  return LIMIT_MIN_US + deg / USABLE_RANGE_DEG * (LIMIT_MAX_US - LIMIT_MIN_US);
}

// Parses exactly NUM_SERVOS comma-separated numbers; returns false if the line is malformed.
bool parseValues(const char *p, float *vals) {
  char *end;
  for (uint8_t i = 0; i < NUM_SERVOS; i++) {
    vals[i] = strtod(p, &end);
    if (end == p) return false;
    if (i < NUM_SERVOS - 1) {
      if (*end != ',') return false;
      p = end + 1;
    }
  }
  return true;
}

void attachAll() {
  for (uint8_t i = 0; i < NUM_SERVOS; i++) {
    currentUs[i] = targetUs[i];
    servos[i].writeMicroseconds((int)currentUs[i]);
    servos[i].attach(SERVO_PINS[i], PULSE_MIN_US, PULSE_MAX_US);
  }
  attached = true;
}

bool handleTargets(const char *p, bool isPulse) {
  float vals[NUM_SERVOS];
  if (!parseValues(p, vals)) return false;
  for (uint8_t i = 0; i < NUM_SERVOS; i++) {
    if (isPulse) {
      targetUs[i] = clampf(vals[i], LIMIT_MIN_US, LIMIT_MAX_US);
    } else {
      targetUs[i] = angleToUs(clampf(vals[i], MIN_ANGLE[i], MAX_ANGLE[i]));
    }
  }
  lastMode = isPulse ? 'U' : 'A';
  if (!attached) attachAll();
  return true;
}

void noteBad(const char *line) {
  badLines++;
  if (!badPending) {
    strncpy(badSample, line, sizeof(badSample) - 1);
    badSample[sizeof(badSample) - 1] = 0;
    badPending = true;
  }
}

void handleLine(const char *line) {
  if ((line[0] == 'A' || line[0] == 'U') && line[1] == ',') {
    if (handleTargets(line + 2, line[0] == 'U')) {
      okLines++;
      digitalWrite(LED_BUILTIN, !digitalRead(LED_BUILTIN));
    } else {
      noteBad(line);
    }
  } else if (line[0] == 'P') {
    Serial.println("OK");
  } else {
    noteBad(line);
  }
}

void readSerial() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (lineLen > 0) {
        lineBuf[lineLen] = 0;
        handleLine(lineBuf);
        lineLen = 0;
      }
    } else if (lineLen < sizeof(lineBuf) - 1) {
      lineBuf[lineLen++] = c;
    } else {
      lineLen = 0;   // overflow: drop the line
      badLines++;
    }
  }
}

void updateServos(float dt) {
  // MAX_DEG_PER_S expressed in microseconds per second.
  float maxStep = MAX_DEG_PER_S / SERVO_RANGE_DEG * (PULSE_MAX_US - PULSE_MIN_US) * dt;
  for (uint8_t i = 0; i < NUM_SERVOS; i++) {
    float diff = targetUs[i] - currentUs[i];
    currentUs[i] += clampf(diff, -maxStep, maxStep);
    servos[i].writeMicroseconds((int)(currentUs[i] + 0.5f));
  }
}

void printStatus() {
  Serial.print("STAT att=");
  Serial.print(attached ? 1 : 0);
  Serial.print(" mode=");
  Serial.print(lastMode);
  Serial.print(" ok=");
  Serial.print(okLines);
  Serial.print(" bad=");
  Serial.print(badLines);
  Serial.print(" us=");
  for (uint8_t i = 0; i < NUM_SERVOS; i++) {
    Serial.print((int)targetUs[i]);
    if (i < NUM_SERVOS - 1) Serial.print(',');
  }
  Serial.println();
  if (badPending) {
    Serial.print("BAD ");
    Serial.println(badSample);
    badPending = false;
  }
}

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  Serial.begin(115200);
  lastUpdateMs = millis();
  lastStatMs = millis();
}

void loop() {
  readSerial();

  unsigned long now = millis();
  if (attached && now - lastUpdateMs >= UPDATE_MS) {
    float dt = (now - lastUpdateMs) / 1000.0f;
    lastUpdateMs = now;
    updateServos(dt);
  }
  if (now - lastStatMs >= STAT_MS) {
    lastStatMs = now;
    printStatus();
  }
}
