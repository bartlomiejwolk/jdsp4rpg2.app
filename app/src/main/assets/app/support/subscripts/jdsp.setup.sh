#where the script resides (passed as argument by the upper script)
	SDIR="$1"
	
#the mountpoint to the tmpfs tree used to mount the new soundfx lib dir.
	TMPFS="$SDIR/support/jdsp4rp5_tmpfs"

#G2 profile: fixed soundfx directory and config targets
		SOUNDFX_DIR=/vendor/lib64/soundfx
		AUDIO_POLICY_TARGETS="/vendor/etc/audio/sku_cliffs_qssi/audio_policy_configuration.xml"
		AUDIO_EFFECTS_TARGETS="/vendor/etc/audio/sku_pineapple/audio_effects.xml /vendor/etc/audio_effects.xml /vendor/etc/audio/audio_effects.xml /vendor/etc/audio/sku_cliffs/audio_effects.xml"

apply_bind_file() {
	src="$1"
	dst="$2"
	[ -f "$dst" ] || return 0
	mount -o bind "$src" "$dst"
	if ! mount | grep -q " on $dst "; then
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

	if [ -n "$AUDIO_POLICY_TARGETS" ]; then
		for tgt in $AUDIO_POLICY_TARGETS; do
			[ -f "$tgt" ] || continue
			umount "$tgt"
		done
	fi

    for m in $(mount |grep tmpfs | grep $(basename $TMPFS)| awk -F' on ' '{print $2}' | awk -F' type ' '{print $1}') ; do
      umount -l "$m"
    done

    for m in $(mount |grep tmpfs | grep "$SOUNDFX_DIR"| awk -F' on ' '{print $2}' | awk -F' type ' '{print $1}') ; do
      umount -l "$m"
    done
	
	if [ -n "$AUDIO_EFFECTS_TARGETS" ]; then
		for tgt in $AUDIO_EFFECTS_TARGETS; do
			[ -f "$tgt" ] || continue
			umount "$tgt"
		done
	fi

    umount /vendor/etc/acdbdata/MTP
    umount /vendor/etc/audio_policy_volumes.xml
    umount /vendor/etc/default_volume_tables.xml
    umount /vendor/etc/mixer_paths_qrd.xml

### /end Cleanup


#Override audio policy configuration
#This is needed to force the low latency path and enable JamesDSP effect processing
#on ull (ultra low latency?) clients too.
	if [ -n "$AUDIO_POLICY_TARGETS" ]; then
		for tgt in $AUDIO_POLICY_TARGETS; do
			[ -f "$tgt" ] || continue
				apply_bind_file "$SDIR/support/conf_files/audio_policy_configuration.xml" "$tgt"
			done
		fi
	
	
#Override audio_effects.xml (all known targets)
#This registers JamesDSP library in the Android Audio effect chain
	if [ -n "$AUDIO_EFFECTS_TARGETS" ]; then
		for tgt in $AUDIO_EFFECTS_TARGETS; do
			[ -f "$tgt" ] || continue
			apply_bind_file "$SDIR/support/conf_files/audio_effects-jdsp.xml" "$tgt"
		done
	fi

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
	
#set permissions and SELinux context
	chown root:root "$SOUNDFX_DIR"/*
	chmod 0644      "$SOUNDFX_DIR"/*
	chcon u:object_r:vendor_configs_file:s0 "$SOUNDFX_DIR"/*

#override (or skip?) qcom acdbdata calibrations fixes missing bass
#on right speaker on low-latenncy path.
  mount -o bind /vendor/etc/acdbdata/QRD /vendor/etc/acdbdata/MTP

#The previous operation lowers the volume for unknown reasons and
#produces a lack in overall bass presence.
#Compensating by highering the default WSA_RX[0,1] Digital Volume
#seems to restore bass presence (mixer_paths_qrd.xml).
    mount -o bind $SDIR/support/conf_files/mixer_paths_qrd.xml /vendor/etc/mixer_paths_qrd.xml
    chown root:root /vendor/etc/mixer_paths_qrd.xml
    chmod 0644      /vendor/etc/mixer_paths_qrd.xml
    chcon u:object_r:vendor_configs_file:s0 /vendor/etc/mixer_paths_qrd.xml

#The previous operation leads to distortion pretty early, so we need to lower
#the volume curves.
  mount -o bind  $SDIR/support/conf_files/default_volume_tables.xml /vendor/etc/default_volume_tables.xml
  mount -o bind  $SDIR/support/conf_files/audio_policy_volumes.xml /vendor/etc/audio_policy_volumes.xml
  chown root:root /vendor/etc/default_volume_tables.xml
  chmod 0644      /vendor/etc/default_volume_tables.xml
  chcon u:object_r:vendor_configs_file:s0 /vendor/etc/default_volume_tables.xml
  chown root:root /vendor/etc/audio_policy_volumes.xml
  chmod 0644      /vendor/etc/audio_policy_volumes.xml
  chcon u:object_r:vendor_configs_file:s0 /vendor/etc/audio_policy_volumes.xml

#Finally, restart audio system
	killall -q audioserver
	killall -q mediaserver

#Start rootless manager components (G2 release ships rootless manager APK only).
if pm path james.dsp >/dev/null 2>&1; then
	am start --user 0 -n james.dsp/me.timschneeberger.rootlessjamesdsp.activity.MainActivity
	am start-foreground-service --user 0 -n james.dsp/me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService

	# Force-open a global effect control session so manager can bind to the system effect.
	am broadcast --user 0 -a android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION \
		--ei android.media.extra.AUDIO_SESSION 0 \
		--es android.media.extra.PACKAGE_NAME james.dsp
fi
