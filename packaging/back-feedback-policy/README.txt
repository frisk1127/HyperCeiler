OS4 back feedback companion policy

The launcher is a native process. SystemUI resolves the previous task and its icon,
and serves it over an abstract Unix socket. The launcher draws the icon on its own
back-arrow canvas. Both endpoints validate peer UIDs. No overlay window is used.

On the tested Android 17 / HyperOS 4 ROM, the launcher runs in platform_app_36 and
SystemUI runs in platform_app. SELinux requires this connectto rule in addition
to the socket peer checks:

  allow platform_app_36 platform_app unix_stream_socket connectto

Install module.prop and sepolicy.rule together as the root module directory
/data/adb/modules/hyperceiler_back_feedback using the device's module deployment
process. The root framework loads sepolicy.rule at boot. For the tested KernelSU
runtime the same rule can be applied immediately, without changing enforcing mode:

  su -c '/data/adb/ksu/bin/ksud sepolicy patch "allow platform_app_36 platform_app unix_stream_socket connectto"'

The policy names match the tested ROM. Other ROMs need their actual launcher and
SystemUI domains checked before adapting the rule. Keep SELinux enforcing.
Removing/disabling the module and rebooting removes the persistent rule; a live
patch remains until reboot. Restore the compatible HyperCeiler build as well if
rolling back the feature.
