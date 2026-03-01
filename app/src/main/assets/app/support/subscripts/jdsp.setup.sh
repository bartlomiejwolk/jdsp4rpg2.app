#where the script resides (passed as argument by the upper script)
	SDIR="$1"
	MODE="$2"
	set -eu
	
#the mountpoint to the tmpfs tree used to mount the new soundfx lib dir.
	TMPFS="$SDIR/support/jdsp4rp5_tmpfs"

#G2 profile: fixed soundfx directory and config targets
		SOUNDFX_DIR=/vendor/lib64/soundfx
		AUDIO_POLICY_TARGET=/vendor/etc/audio/sku_cliffs_qssi/audio_policy_configuration.xml
		AUDIO_EFFECTS_TARGET=/vendor/etc/audio/sku_cliffs/audio_effects.xml

if [ -z "$MODE" ]; then
	echo "ERROR: invalid mode '<missing>'"
	exit 2
fi

case "$MODE" in
	media_only|all_audio) ;;
	*)
		echo "ERROR: invalid mode '$MODE'"
		exit 2
		;;
esac

echo "MODE_SELECTED=$MODE"

is_target_mounted() {
	target="$1"
	mount | awk -F' on | type ' -v tgt="$target" '$2==tgt {found=1} END{exit(found?0:1)}'
}

rollback_on_error() {
	rc="$?"
	echo "SETUP_RESULT=rollback"
	echo "SETUP_ERROR=$rc"
	sh "$SDIR/support/subscripts/jdsp.cleanup.sh" "$SDIR" >/dev/null 2>&1 || true
	echo "EXIT_CODE=$rc"
	exit "$rc"
}

trap rollback_on_error ERR

apply_bind_file() {
	src="$1"
	dst="$2"
	[ -f "$dst" ] || return 0
	mount -o bind "$src" "$dst"
	if ! is_target_mounted "$dst"; then
		echo "apply_bind_file: bind failed src=$src dst=$dst"
		return 1
	fi
	chown root:root "$dst"
	chmod 0644 "$dst"
	chcon u:object_r:vendor_configs_file:s0 "$dst"
	if grep -q '<mixPort name="raw"' "$dst" 2>/dev/null; then
		echo "apply_bind_file: WARNING raw still present in $dst"
	else
		echo "apply_bind_file: raw not present in $dst"
	fi
}

### Cleanup

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

### /end Cleanup


#Override audio policy configuration
#This is needed to force the low latency path and enable JamesDSP effect processing
#on ull (ultra low latency?) clients too.
	if [ "$MODE" = "all_audio" ]; then
		apply_bind_file "$SDIR/support/conf_files/audio_policy_configuration.xml" "$AUDIO_POLICY_TARGET"
		echo "BIND_AUDIO_POLICY=applied"
	else
		echo "BIND_AUDIO_POLICY=skipped"
	fi
	
	
#Override audio_effects.xml (all known targets)
#This registers JamesDSP library in the Android Audio effect chain
	apply_bind_file "$SDIR/support/conf_files/audio_effects-jdsp.xml" "$AUDIO_EFFECTS_TARGET"
	echo "BIND_AUDIO_EFFECTS=applied"

#setup a tmpfs mountpoint
	if [ ! -d "$TMPFS" ]; then
		echo "Creating mountpoint $TMPFS"
		mkdir "$TMPFS"
	fi
	mount -t tmpfs tmpfs $TMPFS

#copy  new effect libs and original soundfx the over it.
	VDIR="$SDIR/support/libs"
	cp $VDIR/libjamesdsp.so $TMPFS/
	cp -av "$SOUNDFX_DIR"/* $TMPFS/
	
#bind mount the cooked TMPFS over the system soundfx dir
	mount -o bind $TMPFS "$SOUNDFX_DIR"
	is_target_mounted "$SOUNDFX_DIR"
	echo "BIND_SOUNDFX_OVERLAY=applied"
	
#set permissions and SELinux context
	chown root:root "$SOUNDFX_DIR"/*
	chmod 0644      "$SOUNDFX_DIR"/*
	chcon u:object_r:vendor_configs_file:s0 "$SOUNDFX_DIR"/*

#override (or skip?) qcom acdbdata calibrations fixes missing bass
#on right speaker on low-latenncy path.
if [ "$MODE" = "all_audio" ]; then
	ALL_AUDIO_OPTIONAL_DEGRADED=0
	if [ -d /vendor/etc/acdbdata/QRD ] && [ -e /vendor/etc/acdbdata/MTP ]; then
		mount -o bind /vendor/etc/acdbdata/QRD /vendor/etc/acdbdata/MTP
		echo "BIND_ACDB=applied"
	else
		echo "BIND_ACDB=skipped_missing"
		ALL_AUDIO_OPTIONAL_DEGRADED=1
	fi

#The previous operation lowers the volume for unknown reasons and
#produces a lack in overall bass presence.
#Compensating by highering the default WSA_RX[0,1] Digital Volume
#seems to restore bass presence (mixer_paths_qrd.xml).
	if [ -f /vendor/etc/mixer_paths_qrd.xml ]; then
		mount -o bind $SDIR/support/conf_files/mixer_paths_qrd.xml /vendor/etc/mixer_paths_qrd.xml
		chown root:root /vendor/etc/mixer_paths_qrd.xml
		chmod 0644      /vendor/etc/mixer_paths_qrd.xml
		chcon u:object_r:vendor_configs_file:s0 /vendor/etc/mixer_paths_qrd.xml
		echo "BIND_MIXER_PATHS=applied"
	else
		echo "BIND_MIXER_PATHS=skipped_missing"
		ALL_AUDIO_OPTIONAL_DEGRADED=1
	fi

#The previous operation leads to distortion pretty early, so we need to lower
#the volume curves.
	if [ -f /vendor/etc/default_volume_tables.xml ]; then
		mount -o bind  $SDIR/support/conf_files/default_volume_tables.xml /vendor/etc/default_volume_tables.xml
		chown root:root /vendor/etc/default_volume_tables.xml
		chmod 0644      /vendor/etc/default_volume_tables.xml
		chcon u:object_r:vendor_configs_file:s0 /vendor/etc/default_volume_tables.xml
		echo "BIND_DEFAULT_VOLUME_TABLES=applied"
	else
		echo "BIND_DEFAULT_VOLUME_TABLES=skipped_missing"
		ALL_AUDIO_OPTIONAL_DEGRADED=1
	fi
	if [ -f /vendor/etc/audio_policy_volumes.xml ]; then
		mount -o bind  $SDIR/support/conf_files/audio_policy_volumes.xml /vendor/etc/audio_policy_volumes.xml
		chown root:root /vendor/etc/audio_policy_volumes.xml
		chmod 0644      /vendor/etc/audio_policy_volumes.xml
		chcon u:object_r:vendor_configs_file:s0 /vendor/etc/audio_policy_volumes.xml
		echo "BIND_AUDIO_POLICY_VOLUMES=applied"
	else
		echo "BIND_AUDIO_POLICY_VOLUMES=skipped_missing"
		ALL_AUDIO_OPTIONAL_DEGRADED=1
	fi
	if [ "$ALL_AUDIO_OPTIONAL_DEGRADED" -eq 1 ]; then
		echo "ALL_AUDIO_OPTIONAL_BINDS=degraded_missing_targets"
	else
		echo "ALL_AUDIO_OPTIONAL_BINDS=applied"
	fi
else
	echo "BIND_ACDB=skipped"
	echo "BIND_MIXER_PATHS=skipped"
	echo "BIND_DEFAULT_VOLUME_TABLES=skipped"
	echo "BIND_AUDIO_POLICY_VOLUMES=skipped"
fi

#Finally, restart audio system
	killall -q audioserver || true
	killall -q mediaserver || true

#Start rootless manager components (G2 release ships rootless manager APK only).
if pm path james.dsp >/dev/null 2>&1; then
	if am start --user 0 -n james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity >/dev/null 2>&1; then
		echo "MANAGER_START=ok"
	else
		echo "MANAGER_START=failed"
	fi
	if am start-foreground-service --user 0 -n james.dsp/me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService >/dev/null 2>&1; then
		echo "MANAGER_SERVICE_START=ok"
	else
		echo "MANAGER_SERVICE_START=failed"
	fi

	# Force-open a global effect control session so manager can bind to the system effect.
	if am broadcast --user 0 -a android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION \
		--ei android.media.extra.AUDIO_SESSION 0 \
		--es android.media.extra.PACKAGE_NAME james.dsp >/dev/null 2>&1; then
		echo "MANAGER_OPEN_SESSION=ok"
	else
		echo "MANAGER_OPEN_SESSION=failed"
	fi
else
	echo "MANAGER_PRESENT=missing"
fi

# Final exact-target verification before declaring success.
is_target_mounted "$SOUNDFX_DIR"
is_target_mounted "$AUDIO_EFFECTS_TARGET"
if [ "$MODE" = "all_audio" ]; then
	is_target_mounted "$AUDIO_POLICY_TARGET"
fi

trap - ERR
echo "SETUP_RESULT=success"
echo "EXIT_CODE=0"
