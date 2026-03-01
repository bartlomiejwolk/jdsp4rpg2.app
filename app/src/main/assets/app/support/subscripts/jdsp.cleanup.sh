#where the script resides (passed as argument by the upper script)
	SDIR="$1"
	set -eu
#the mountpoint to the tmpfs tree
	TMPFS="$SDIR/support/jdsp4rp5_tmpfs"
#G2 profile: fixed soundfx directory and config targets
		SOUNDFX_DIR=/vendor/lib64/soundfx
		AUDIO_POLICY_TARGET=/vendor/etc/audio/sku_cliffs_qssi/audio_policy_configuration.xml
		AUDIO_EFFECTS_TARGET=/vendor/etc/audio/sku_cliffs/audio_effects.xml

is_target_mounted() {
	target="$1"
	mount | awk -F' on | type ' -v tgt="$target" '$2==tgt {found=1} END{exit(found?0:1)}'
}

#cleanup
	[ -f "$AUDIO_POLICY_TARGET" ] && umount "$AUDIO_POLICY_TARGET" || true
	mount | awk -F' on | type ' -v tgt="$TMPFS" '$2==tgt {print}' | while IFS= read -r line; do
		m="$(echo "$line" | awk -F' on ' '{print $2}' | awk -F' type ' '{print $1}')"
		[ -n "$m" ] && umount -l "$m" || true
	done || true

	mount | awk -F' on | type ' -v tgt="$SOUNDFX_DIR" '$2==tgt {print}' | while IFS= read -r line; do
		m="$(echo "$line" | awk -F' on ' '{print $2}' | awk -F' type ' '{print $1}')"
		[ -n "$m" ] && umount -l "$m" || true
	done || true
	
	[ -f "$AUDIO_EFFECTS_TARGET" ] && umount "$AUDIO_EFFECTS_TARGET" || true

	umount /vendor/etc/acdbdata/MTP || true

  umount /vendor/etc/audio_policy_volumes.xml || true
  umount /vendor/etc/default_volume_tables.xml || true
  umount /vendor/etc/mixer_paths_qrd.xml || true

#restart audio system
	killall -q audioserver || true
	killall -q mediaserver || true

#Close global effect control session for manager consistency.
am broadcast --user 0 -a android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION \
	--ei android.media.extra.AUDIO_SESSION 0 \
	--es android.media.extra.PACKAGE_NAME james.dsp || true

for target in \
	"$TMPFS" \
	"$SOUNDFX_DIR" \
	"$AUDIO_EFFECTS_TARGET" \
	"$AUDIO_POLICY_TARGET" \
	"/vendor/etc/mixer_paths_qrd.xml" \
	"/vendor/etc/audio_policy_volumes.xml" \
	"/vendor/etc/default_volume_tables.xml" \
	"/vendor/etc/acdbdata/MTP"
do
	if is_target_mounted "$target"; then
		echo "CLEANUP_RESULT=failed"
		echo "EXIT_CODE=1"
		exit 1
	fi
done

echo "CLEANUP_RESULT=success"
echo "EXIT_CODE=0"
