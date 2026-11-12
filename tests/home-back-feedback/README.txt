OS4 native back gesture feedback

HoldState in native code owns activation and cancellation. The Java state validates
session ownership and the final task snapshot; its old hold helpers are not the
activation timer for OS4. Native activation uses the launcher's verified full-pull
boundary, followed by a 300 ms hold (at least 350 ms after DOWN). A 4 dp retreat
from the deepest point cancels, including after overpull. Stationary retreat does
not rearm; an inward pull must enter the activation region again.

Run the platform-independent state checks from the repository root:

  HC_BACK_TEST_OUT=$(mktemp -d)
  c++ -std=c++20 -O1 -Iapp/src/main/cpp tests/home-back-feedback/NativeHoldTest.cpp -o "$HC_BACK_TEST_OUT/hold-test"
  "$HC_BACK_TEST_OUT/hold-test"
  javac -d "$HC_BACK_TEST_OUT" library/libhook/src/main/java/com/sevtinge/hyperceiler/libhook/rules/home/gesture/BackHoldState.java tests/home-back-feedback/StateTest.java
  java -cp "$HC_BACK_TEST_OUT" com.sevtinge.hyperceiler.libhook.rules.home.gesture.StateTest

ArrowResolverTest additionally needs libapp_launcher.so from the tested launcher
APK. The fixture is not committed. It checks the known 88 dp boundary, a modified
96 dp boundary, and rejection of broken drawing, lifetime and density contracts.

  c++ -std=c++20 -O1 -Iapp/src/main/cpp tests/home-back-feedback/ArrowResolverTest.cpp -o "$HC_BACK_TEST_OUT/arrow-test"
  "$HC_BACK_TEST_OUT/arrow-test" /path/to/libapp_launcher.so
  rm -rf "$HC_BACK_TEST_OUT"

Tested launcher: RELEASE-8.01.02.7755-260904-10151930-R, Android 17 / HyperOS 4.
Runtime resolution uses instruction relationships and imported APIs, not those
fixture addresses or private symbol names.

TouchProbe is an optional privileged device probe. Compile against android.jar,
convert with D8, and run with app_process and input-injection permission. It uses
1200x2608 coordinates and actively operates the screen. Arguments are:

  side(left/right) holdMs finish(release/retract/rearm/cancel) stepMs distancePx retreatPx

Retraction waits 450 ms before release to check that a stationary finger does not
rearm. The rearm case then pulls inward and holds again. Inspect the
HyperCeiler.BackArrow and HyperCeiler.BackFeedback logcat tags: full pull should
produce ready/shown/confirmed, retreat should produce cancelled, and only an
accepted release should produce commit. Closing a source activity with ordinary
back can expose the previous app, so foreground package alone is not a reliable
assertion of a quick switch.

The preview transport requires the narrowly scoped policy described in
packaging/back-feedback-policy/README.txt on the tested ROM.
