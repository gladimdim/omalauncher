# Builds SD Launcher's adaptive icon layers as SVG: the SUPER DESKTOP thunderbolt mascot (its ASCII art,
# winking) on a home-screen tile. 108 x 108 units = the adaptive icon's 108 dp; keep art inside the 66 dp safe zone.
import sys
from xml.sax.saxutils import escape
ACID, DIM = "#39FF14", "#1f7a12"
art = [
    "      /======/",
    "     /======/",
    "    / o - /",
    "   /  \\_//",
    "  /       /___",
    " /===========/",
    " '----/=====/",
    "     /====/",
    "    /===/",
    "   /==/",
    "  /_/",
]
FS, LH, CW = 7.0, 5.35, 7.0 * 0.6          # font size, line height, mono cell width (JetBrains Mono ~0.6 em)
w = max(len(l) for l in art) * CW
h = len(art) * LH
x0, y0 = 54 - w / 2, 54 - h / 2 + FS * 0.78

def text(fill, extra=""):
    return "".join(
        f'<text x="{x0:.2f}" y="{y0 + i * LH:.2f}" fill="{fill}" {extra} xml:space="preserve">{escape(l)}</text>'
        for i, l in enumerate(art))

font = 'font-family="JetBrains Mono" font-weight="800" font-size="%.2f"' % FS
head = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="%d" height="%d">'

def background(px):
    return (head % (px, px)) + f'''
<defs><radialGradient id="g" cx="50%" cy="42%" r="70%"><stop offset="0" stop-color="#16201a"/><stop offset="1" stop-color="#07090c"/></radialGradient></defs>
<rect width="108" height="108" fill="url(#g)"/>
<g fill="{ACID}" fill-opacity=".07">''' + "".join(
        f'<rect x="{x}" y="{y}" width="1.2" height="1.2" rx=".3"/>' for x in range(6, 108, 8) for y in range(6, 108, 8)) + "</g></svg>"

def foreground(px):
    # A launcher tile (the home-screen frame) holding the mascot, with a neon glow behind the text.
    return (head % (px, px)) + f'''
<defs><filter id="glow" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="1.4"/></filter></defs>
<g {font} stroke="{ACID}" stroke-width="1.1" filter="url(#glow)" opacity=".75">{text(ACID)}</g>
<g {font} stroke="{ACID}" stroke-width=".42" stroke-linejoin="round">{text(ACID)}</g></svg>'''

def monochrome(px):
    return (head % (px, px)) + f'''
<g {font} stroke="#fff" stroke-width=".42" stroke-linejoin="round">{text("#fff")}</g></svg>'''

def full(px):
    # Store-style preview: background + foreground, masked to the launcher's rounded square.
    b, f = background(px), foreground(px)
    inner = lambda s: s[s.index(">") + 1 : s.rindex("</svg>")]
    return (head % (px, px)) + f'<defs><clipPath id="m"><rect width="108" height="108" rx="24"/></clipPath></defs><g clip-path="url(#m)">{inner(b)}{inner(f)}</g></svg>'

for name, fn in [("background", background), ("foreground", foreground), ("monochrome", monochrome), ("full", full)]:
    open(f"{name}.svg", "w").write(fn(int(sys.argv[1]) if len(sys.argv) > 1 else 432))
