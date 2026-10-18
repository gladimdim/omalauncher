#!/bin/sh
# Fork: renders SD Launcher's icon (the SUPER DESKTOP thunderbolt mascot) into app/src/sd/res and the 512 px
# store image. Needs rsvg-convert and the JetBrains Mono font. Edit make.py, then run this.
set -e
cd "$(dirname "$0")"
tmp=$(mktemp -d)
python3 -I make.py 108 && cp background.svg foreground.svg monochrome.svg full.svg "$tmp/" && rm -f background.svg foreground.svg monochrome.svg full.svg
res=../../app/src/sd/res
for d in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
    dir="$res/mipmap-${d%%:*}"; px=${d##*:}
    mkdir -p "$dir"
    for layer in background foreground monochrome; do rsvg-convert -w "$px" -h "$px" "$tmp/$layer.svg" -o "$dir/sd_icon_$layer.png"; done
done
rsvg-convert -w 512 -h 512 "$tmp/full.svg" -o ../../docs/superdesktop/media/icon-512.png
command -v oxipng >/dev/null && oxipng -q -o 3 --strip safe "$res"/mipmap-*/sd_icon_*.png ../../docs/superdesktop/media/icon-512.png
rm -rf "$tmp"
