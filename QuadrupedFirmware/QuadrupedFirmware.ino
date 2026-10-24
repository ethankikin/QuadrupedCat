// Quadruped servo driver for Arduino Mega (8 servos wired directly: hip + knee per leg).
//
// The phone computes the gait, inverse kinematics and balance; this sketch only moves the
// servos to the angles it is given.
//
// Serial protocol (115200 baud, ASCII, newline-terminated), phone -> Arduino:
//   A,<a0>,<a1>,...,<a7>   target angles in degrees, order: hip, knee for each of
//                          FL, FR, RL, RR (index = leg * 2 + joint)
//   P                      ping; replies "OK"
// Servos are attached on the first valid A line (jumping straight to that pose), so nothing
// moves at power-up. After that each servo is slew-rate limited. If no A line arrives for
// WATCHDOG_MS the servos simply hold their last target.

#include <Servo.h>

const uint8_t NUM_SERVOS = 8;

// Index = leg * 2 + joint. FL hip/knee, FR hip/knee, RL hip/knee, RR hip/knee.
const uint8_t SERVO_PINS[NUM_SERVOS] = {22, 23, 24, 25, 26, 27, 28, 29};

// Safety limits per servo, degrees. Tighten these to what the mechanics allow.
const int MIN_ANGLE[NUM_SERVOS] = {10, 10, 10, 10, 10, 10, 10, 10};
const int MAX_ANGLE[NUM_SERVOS] = {170, 170, 170, 170, 170, 170, 170, 170};

const float MAX_DEG_PER_S = 360.0f;   // slew limit
const unsigned long UPDATE_MS = 10;

Servo servos[NUM_SERVOS];
float target[NUM_SERVOS];
float current[NUM_SERVOS];
bool attached = false;
unsigned long lastUpdateMs = 0;

char lineBuf[96];
uint8_t lineLen = 0;

static float clampf(float v, float lo, float hi) {
  return v < lo ? lo : (v > hi ? hi : v);
}

void handleAngles(const char *p) {
  float vals[NUM_SERVOS];
  char *end;
  for (uint8_t i = 0; i < NUM_SERVOS; i++) {
    vals[i] = strtod(p, &end);
    if (end == p) return;                      // missing value: reject the whole line
    if (i < NUM_SERVOS - 1) {
      if (*end != ',') return;
      p = end + 1;
    }
  }
  for (uint8_t i = 0; i < NUM_SERVOS; i++) {
    target[i] = clampf(vals[i], MIN_ANGLE[i], MAX_ANGLE[i]);
  }
  if (!attached) {
    for (uint8_t i = 0; i < NUM_SERVOS; i++) {
      current[i] = target[i];
      servos[i].write((int)current[i]);
      servos[i].attach(SERVO_PINS[i]);
    }
    attached = true;
  }
}

void handleLine(const char *line) {
  if (line[0] == 'A' && line[1] == ',') {
    handleAngles(line + 2);
  } else if (line[0] == 'P') {
    Serial.println("OK");
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
    }
  }
}

void updateServos(float dt) {
  float maxStep = MAX_DEG_PER_S * dt;
  for (uint8_t i = 0; i < NUM_SERVOS; i++) {
    float diff = target[i] - current[i];
    current[i] += clampf(diff, -maxStep, maxStep);
    servos[i].write((int)(current[i] + 0.5f));
  }
}

void setup() {
  Serial.begin(115200);
  lastUpdateMs = millis();
}

void loop() {
  readSerial();

  unsigned long now = millis();
  if (attached && now - lastUpdateMs >= UPDATE_MS) {
    float dt = (now - lastUpdateMs) / 1000.0f;
    lastUpdateMs = now;
    updateServos(dt);
  }
}
