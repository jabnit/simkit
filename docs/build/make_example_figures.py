"""Figures for Appendix A of fire_thermal_analysis_method.md. Output: ../images/method_example_A*.png and .svg.

Burner axis vertical, flame upward. Zone of position from the zone contours extruded along the axis
(default procedure): radial distance from the axis in the section through the axis. Face rules of the default procedure.
"""
import numpy as np
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Circle, Patch, Polygon, Rectangle

from make_method_figures import GREY, INK, LIGHT, OUT, ZC, dim_line, ext_line, save

S = 100.0
RADII = {"Z1": 100.0, "Z2": 200.0, "Z3": 350.0}
PART = "#E6E6E6"
OVER = {"wrapped": (0, (1, 1.2)), "next": (0, (3, 1.6))}
NOTE = dict(fontsize=7.5, linespacing=1.2)
LEADER = dict(arrowstyle="-", lw=0.55, color=INK, shrinkA=1, shrinkB=0)
BOX = dict(facecolor="white", edgecolor="none", pad=0.8)


def zone_of(r):
    for z in ("Z1", "Z2", "Z3"):
        if r <= RADII[z] + 1e-9:
            return z
    return None


def rect(y0, y1, x0, x1):
    return [(y0, x0), (y1, x0), (y1, x1), (y0, x1)]


def offset_path(P, t):
    # offset of an open polyline by t to the left of the travel direction (miter joins)
    P = np.asarray(P, float)
    segs = []
    for a, b in zip(P[:-1], P[1:]):
        d = (b - a) / np.hypot(*(b - a))
        n = np.array([-d[1], d[0]])
        segs.append((a + t * n, b + t * n))
    out = [segs[0][0]]
    for (a1, b1), (a2, b2) in zip(segs[:-1], segs[1:]):
        d1, d2 = b1 - a1, b2 - a2
        s = np.linalg.solve(np.array([d1, -d2]).T, a2 - a1)[0]
        out.append(a1 + s * d1)
    out.append(segs[-1][1])
    return np.array(out)


class Section:
    """Section through the burner axis. Plot coordinates: (position across the axis, x from cone exit)."""

    def __init__(self, ax, ylim, xlim, label, across="y", lw=2.6):
        self.ax = ax
        self.lw = lw
        ax.set_xlim(*ylim)
        ax.set_ylim(*xlim)
        ax.set_aspect("equal")
        ax.apply_aspect()
        w_pt = ax.get_position().width * ax.figure.get_figwidth() * 72.0
        self.mm_pt = (ylim[1] - ylim[0]) / w_pt
        ax.set_xlabel(f"{across} (mm)", fontsize=8)
        ax.set_ylabel("x from cone exit (mm)", fontsize=8)
        ax.tick_params(labelsize=7.5)
        ax.set_title(label, fontsize=8.5, loc="left")

    def frame(self, aux=(), ref=True, zone_labels=True, cone=True, cone_label=True):
        ax = self.ax
        y0, y1 = ax.get_xlim()
        x0, x1 = ax.get_ylim()
        for r in RADII.values():
            for sg in (1, -1):
                if y0 < sg * r < y1:
                    ax.plot([sg * r] * 2, [max(0, x0), x1], color=GREY, lw=0.6, ls="--", zorder=1)
        if zone_labels:
            edges = [0.0, 100.0, 200.0, 350.0]
            for i, z in enumerate(("Z1", "Z2", "Z3")):
                for sg in (1, -1):
                    lo, hi = sorted((sg * edges[i], sg * edges[i + 1]))
                    lo, hi = max(lo, y0), min(hi, y1)
                    if hi - lo > 0.06 * (y1 - y0):
                        ax.text((lo + hi) / 2, x1 - 0.03 * (x1 - x0), z, ha="center", va="top",
                                fontsize=8, zorder=6)
        ax.plot([0, 0], [x0, x1], color=INK, lw=0.6, ls=(0, (8, 3, 1, 3)), zorder=1)
        if cone and x0 < 0:
            ax.add_patch(Polygon([(-38, 0), (38, 0), (55, -160), (-55, -160)], closed=True,
                                 facecolor="#D9D9D9", edgecolor=INK, lw=0.8, zorder=2))
            if cone_label:
                ax.text(-60, x0 / 2, "Burner cone\n(schematic)", ha="right", va="center",
                        **NOTE)
        if ref:
            ax.plot([y0, y1], [S, S], color=GREY, lw=0.7, zorder=1)
        for f in aux:
            ax.plot([y0, y1], [f * S] * 2, color=GREY, lw=0.6, ls=(0, (6, 2, 1, 2)), zorder=1)
            ax.text(y1 - 0.01 * (y1 - y0), f * S - 0.01 * (x1 - x0), f"x = {f:g} L", ha="right",
                    va="top",
                    fontsize=7, color=GREY)

    def stand_off(self, y=-385.0):
        ax = self.ax
        sg = 1 if y < 0 else -1
        dim_line(ax, y, 0, y, S, "100", offset=(8 * sg, 0.12 * S),
                 ha="left" if sg > 0 else "right", va="center", fs=7.5)
        ext_line(ax, y - 10 * sg, S, y + 20 * sg, S)
        ext_line(ax, y - 10 * sg, 0, -38 * sg, 0)

    def part(self, pts, fc=PART):
        self.ax.add_patch(Polygon(pts, closed=True, facecolor=fc, edgecolor=INK, lw=0.5,
                                  zorder=3))

    def face(self, pts, side, cat="visible", zone=None):
        """Face along pts with outward normal side (one vector, or one per point)."""
        P = np.asarray(pts, float)
        side = np.asarray(side, float)
        if side.ndim == 1:
            dense = []
            for a, b in zip(P[:-1], P[1:]):
                m = max(2, int(np.hypot(*(b - a)) / 0.5) + 1)
                dense.append(a + (b - a) * np.linspace(0, 1, m)[:, None])
            P = np.vstack(dense)
            side = np.broadcast_to(side / np.hypot(*side), P.shape)
        Q = P + (self.lw / 2 + 0.4) * self.mm_pt * side
        r = np.abs(P[:, 0])
        colours = []
        for i in range(len(P) - 1):
            if cat == "none":
                colours.append(LIGHT)
            else:
                # an element straddling a contour takes the hotter zone
                z = zone or zone_of(min(r[i], r[i + 1]))
                if cat == "next" and z:
                    order = list(RADII)
                    z = order[min(order.index(z) + 1, len(order) - 1)]
                colours.append(ZC[z] if z else LIGHT)
        i = 0
        while i < len(colours):
            j = i
            while j + 1 < len(colours) and colours[j + 1] == colours[i]:
                j += 1
            self.ax.plot(Q[i:j + 2, 0], Q[i:j + 2, 1], color=colours[i], lw=self.lw,
                         solid_capstyle="butt", zorder=4)
            i = j + 1
        if cat in OVER:
            # overlay only where a zone flux is applied
            i = 0
            while i < len(colours):
                if colours[i] == LIGHT:
                    i += 1
                    continue
                j = i
                while j + 1 < len(colours) and colours[j + 1] != LIGHT:
                    j += 1
                self.ax.plot(Q[i:j + 2, 0], Q[i:j + 2, 1], color=INK, lw=0.9, ls=OVER[cat], zorder=5)
                i = j + 1

    def note(self, xy, xytext, text, ha="left", va="center"):
        self.ax.annotate(text, xy=xy, xytext=xytext, ha=ha, va=va, arrowprops=LEADER, zorder=7,
                         bbox=BOX, **NOTE)

    def text(self, x, y, text, ha="left", va="center"):
        self.ax.text(x, y, text, ha=ha, va=va, zorder=7, bbox=BOX, **NOTE)

    def box(self, y0, y1, x0, x1, label):
        self.ax.add_patch(Rectangle((y0, x0), y1 - y0, x1 - x0, facecolor="none", edgecolor=INK,
                                    lw=0.6, zorder=8))
        self.ax.text(y1 + 4, x1, label, ha="left", va="bottom", fontsize=7.5, zorder=8)


class ZoneMap:
    """Thermal model zoning on the specimen, viewed along the burner axis from the burner."""

    def __init__(self, ax, lim, label, h="y", v="z"):
        self.ax = ax
        ax.set_xlim(-lim, lim)
        ax.set_ylim(-lim, lim)
        ax.set_aspect("equal")
        ax.set_xlabel(f"{h} (mm)", fontsize=8)
        ax.set_ylabel(f"{v} (mm)", fontsize=8)
        ax.tick_params(labelsize=7.5)
        ax.set_title(label, fontsize=8.5, loc="left")

    def surface(self, poly, z=2):
        ax = self.ax
        clip = Polygon(poly, closed=True, facecolor=LIGHT, edgecolor="none", zorder=z)
        ax.add_patch(clip)
        for zn in ("Z3", "Z2", "Z1"):
            c = Circle((0, 0), RADII[zn], facecolor=ZC[zn], edgecolor="none", zorder=z)
            ax.add_patch(c)
            c.set_clip_path(clip)
        ax.add_patch(Polygon(poly, closed=True, facecolor="none", edgecolor=INK, lw=0.6,
                             zorder=z + 0.5))

    def hidden(self, poly):
        self.ax.add_patch(Polygon(poly, closed=True, facecolor="none", edgecolor=INK, lw=0.5,
                                  ls=(0, (3, 2)), zorder=8))

    def contours(self, section="h"):
        ax = self.ax
        lim = ax.get_xlim()[1]
        for zn, r in RADII.items():
            ax.add_patch(Circle((0, 0), r, facecolor="none", edgecolor=GREY, lw=0.6, ls="--",
                                zorder=9))
            if r < lim:
                a = np.radians(128)
                ax.text(r * np.cos(a), r * np.sin(a), zn, fontsize=7.5, ha="center",
                        va="center", zorder=10, bbox=dict(facecolor="white", edgecolor="none",
                                                          pad=0.5))
        ax.plot([-12, 12], [0, 0], color=INK, lw=0.8, zorder=10)
        ax.plot([0, 0], [-12, 12], color=INK, lw=0.8, zorder=10)
        cut = dict(color=INK, lw=0.7, ls=(0, (8, 3, 1, 3)), zorder=10)
        if section == "h":
            ax.plot([-lim, lim], [0, 0], **cut)
            ax.text(-0.97 * lim, 0.03 * lim, "section", fontsize=7, ha="left", va="bottom",
                    zorder=10)
        else:
            ax.plot([0, 0], [-lim, lim], **cut)
            ax.text(-0.03 * lim, -0.97 * lim, "section", fontsize=7, ha="right", va="bottom",
                    rotation=90, zorder=10)

    def note(self, xy, xytext, text, ha="left", va="center"):
        self.ax.annotate(text, xy=xy, xytext=xytext, ha=ha, va=va, arrowprops=LEADER, zorder=11,
                         bbox=BOX, **NOTE)

    def text(self, x, y, text, ha="left", va="center"):
        self.ax.text(x, y, text, ha=ha, va=va, zorder=11, bbox=BOX, **NOTE)


def layout_big():
    """Zoning and detail on top, section through the axis below."""
    fig = plt.figure(figsize=(10.0, 8.8))
    gs = fig.add_gridspec(2, 2, height_ratios=[1.0, 0.74], width_ratios=[1.0, 1.1], left=0.075,
                          right=0.985, top=0.965, bottom=0.12, hspace=0.24, wspace=0.22)
    return fig, fig.add_subplot(gs[0, 0]), fig.add_subplot(gs[0, 1]), fig.add_subplot(gs[1, :])


def layout_row():
    """Zoning, section and variant in one row."""
    fig = plt.figure(figsize=(10.0, 4.9))
    gs = fig.add_gridspec(1, 3, width_ratios=[1.0, 0.8, 0.8], left=0.065, right=0.99, top=0.93,
                          bottom=0.27, wspace=0.3)
    return fig, [fig.add_subplot(g) for g in gs]


def legend(fig, cats, y=0.0):
    h = [Patch(facecolor=ZC[z], edgecolor="none", label=f"{z} applied") for z in ZC]
    h.append(Patch(facecolor=LIGHT, edgecolor="none",
                   label="Not reached by the flame"))
    text = {"wrapped": "Hidden face, flame wraps: zone of position",
            "next": "Hidden face: next zone"}
    for c in cats:
        h.append(Line2D([], [], color=INK, lw=0.9, ls=OVER[c], label=text[c]))
    fig.legend(handles=h, loc="lower left", bbox_to_anchor=(0.06, y + 0.025), ncol=3,
               frameon=False, fontsize=7.5, handlelength=2.2, columnspacing=1.6)


def skin_faces(sec, y0, y1, x_front, t, gaps=(), edge=True):
    """Flame-side face of a wall from y0 to y1 at x_front, except the y intervals in gaps; back face not reached."""
    a = y0
    for g0, g1 in sorted(gaps):
        if g0 > a:
            sec.face([(a, x_front), (g0, x_front)], (0, -1))
        a = max(a, g1)
    if a < y1:
        sec.face([(a, x_front), (y1, x_front)], (0, -1))
    sec.face([(y0, x_front + t), (y1, x_front + t)], (0, 1), cat="none")
    if edge:
        sec.face([(y0, x_front), (y0, x_front + t)], (-1, 0))
        sec.face([(y1, x_front), (y1, x_front + t)], (1, 0))


OVERVIEW = dict(ylim=(-420, 420), xlim=(-80, 165))


# A.1 Stiffened skin, smooth side toward the burner

def a1_specimen(sec, t=3.0):
    sec.part(rect(-250, 250, S, S + t))
    for yc in (-150, 0, 150):
        for p in (rect(yc - 20, yc, S + t, S + t + 2), rect(yc - 1, yc + 1, S + t, S + t + 25),
                  rect(yc, yc + 20, S + t + 23, S + t + 25)):
            sec.part(p)
    skin_faces(sec, -250, 250, S, t)


def fig_a1():
    fig, axm, axd, axs = layout_big()
    m = ZoneMap(axm, 430, "(a) Zoning on the specimen, view from the burner")
    m.surface(rect(-250, 250, -250, 250))
    for yc in (-150, 0, 150):
        m.hidden(rect(yc - 20, yc + 20, -250, 250))
    m.contours()
    m.note((150, -200), (170, -330), "Z-stringers on the far side\n(hidden lines)", ha="center")

    det = Section(axd, (120, 265), (80, 140), "(c) Detail, panel edge", lw=3.0)
    det.frame(zone_labels=True, cone=False)
    a1_specimen(det)
    det.note((175, S - 1.5), (150, 86), "Skin, flame side: zone of position")
    det.note((150, S + 15), (190, 125), "Skin back face and stringers:\nnot impinged (edges outside the\nhottest-zone contour)")

    sec = Section(axs, **OVERVIEW, label="(b) Section through the burner axis", lw=2.0)
    sec.frame()
    a1_specimen(sec)
    sec.stand_off()
    sec.box(120, 265, 80, 140, "(c)")
    legend(fig, ())
    save(fig, "method_example_A1")


# A.2 Stiffened skin, stringer side toward the burner

def a2_stringer(sec, yc, x_skin, h=25.0, f=20.0, t=2.0):
    xf = x_skin - h
    sec.part(rect(yc - f, yc, x_skin - t, x_skin))          # attached flange
    sec.part(rect(yc - t / 2, yc + t / 2, xf, x_skin - t))  # web
    sec.part(rect(yc, yc + f, xf, xf + t))                  # free flange
    sec.face([(yc, xf), (yc + f, xf)], (0, -1))
    sec.face([(yc + f, xf), (yc + f, xf + t)], (1, 0))
    sec.face([(yc - t / 2, xf), (yc - t / 2, x_skin - t)], (-1, 0))
    sec.face([(yc - f, x_skin - t), (yc - t / 2, x_skin - t)], (0, -1))
    sec.face([(yc - f, x_skin - t), (yc - f, x_skin)], (-1, 0))
    # pocket under the free flange: cavity, lowest-numbered zone crossing the opening (step 8)
    z_open = zone_of(abs(yc + f) if abs(yc + f) < abs(yc) else abs(yc))
    sec.face([(yc + t / 2, xf + t), (yc + f, xf + t)], (0, 1), cat="wrapped", zone=z_open)
    sec.face([(yc + t / 2, xf + t), (yc + t / 2, x_skin - t)], (1, 0), cat="wrapped", zone=z_open)
    sec.face([(yc + t / 2, x_skin), (yc + f, x_skin)], (0, -1), cat="wrapped", zone=z_open)


def a2_specimen(sec, t=3.0, y0=-250, y1=250, edge=True):
    sec.part(rect(y0, y1, S, S + t))
    skin_faces(sec, y0, y1, S, t, gaps=[(yc - 20, yc + 20) for yc in (-150, 0, 150)], edge=edge)
    for yc in (-150, 0, 150):
        if y0 < yc < y1:
            a2_stringer(sec, yc, S)


def fig_a2():
    fig, axm, axd, axs = layout_big()
    m = ZoneMap(axm, 430, "(a) Zoning on the specimen, view from the burner")
    m.surface(rect(-250, 250, -250, 250))
    for yc in (-150, 0, 150):
        m.surface(rect(yc - 20, yc + 20, -250, 250), z=3)
    m.contours()
    m.note((15, 230), (120, 380), "Stringer on the axis: Z1 for |z| ≤ 100,\nZ2 to 200, Z3 to the panel edge",
           ha="center")
    axm.plot(75, 0, marker="x", color=INK, ms=6, mew=1.0, zorder=12)
    m.note((78, -5), (300, -120), "Second position:\nbay centre on the\naxis (step 10)", ha="center")
    m.text(-415, -400, "Free flanges cover 13 % of the hottest-zone area.\nReference surface: skin",
           va="bottom")

    det = Section(axd, (-32, 32), (66, 108), "(c) Detail, stringer on the axis", lw=3.0)
    det.frame(zone_labels=False, cone=False, aux=(0.75,))
    a2_specimen(det, y0=-32, y1=32, edge=False)
    det.note((10, 75.5), (-28, 70.5), "Free flange top at x = 0.75 L: Z1,\nprojection, recorded")
    det.note((20.5, 76), (14, 85), "Edges, open web side,\nattached flange: Z1", ha="left")
    det.note((10, 96), (-29, 87), "Pocket under the free flange:\ncavity d/w = 0.8, Z1")
    det.note((-26, S - 0.5), (-22, 104.5), "Skin: Z1", ha="center")

    sec = Section(axs, **OVERVIEW, label="(b) Section through the burner axis", lw=2.0)
    sec.frame(aux=(0.5,))
    a2_specimen(sec)
    sec.stand_off()
    sec.box(-32, 32, 66, 108, "(c)")
    sec.note((150, S - 26), (240, 40), "Stringers at 150 mm: same faces,\nZ2 in this section", ha="left")
    sec.note((-200, S + 3), (-240, 130), "Skin far side: not impinged", ha="center")
    legend(fig, ("wrapped",))
    save(fig, "method_example_A2")


# A.3 Hat stringer toward the burner

def hat_outer(x_skin, h=30.0, crown=25.0, angle=70.0, foot=15.0, t=2.0):
    dy = (h - t) / np.tan(np.radians(angle))
    yc = crown / 2
    xb = x_skin - t
    return np.array([(-(yc + dy + foot), xb), (-(yc + dy), xb), (-yc, x_skin - h),
                     (yc, x_skin - h), (yc + dy, xb), (yc + dy + foot, xb)])


def a3_specimen(sec, t=3.0, y0=-250, y1=250, edge=True, th=2.0):
    O = hat_outer(S, t=th)
    I = offset_path(O, th)
    half = O[-1, 0]
    sec.part(rect(y0, y1, S, S + t))
    skin_faces(sec, y0, y1, S, t, gaps=[(-half, half)], edge=edge)
    sec.part(np.vstack([O, I[::-1]]))
    for a, b in zip(O[:-1], O[1:]):
        d = (b - a) / np.hypot(*(b - a))
        sec.face([a, b], (d[1], -d[0]))
    sec.face([O[0], I[0]], (-1, 0))
    sec.face([O[-1], I[-1]], (1, 0))
    inner = I[1:-1]
    for a, b in zip(inner[:-1], inner[1:]):
        d = (b - a) / np.hypot(*(b - a))
        sec.face([a, b], (-d[1], d[0]), cat="none")
    sec.face([(inner[0, 0], S), (inner[-1, 0], S)], (0, -1), cat="none")
    return half


def fig_a3():
    fig, axm, axd, axs = layout_big()
    half = hat_outer(S)[-1, 0]
    m = ZoneMap(axm, 430, "(a) Zoning on the specimen, view from the burner")
    m.surface(rect(-250, 250, -250, 250))
    m.surface(rect(-half, half, -250, 250), z=3)
    m.contours()

    det = Section(axd, (-48, 48), (60, 108), "(c) Detail, hat on the axis", lw=3.0)
    det.frame(zone_labels=False, cone=False, aux=(0.7,))
    a3_specimen(det, y0=-48, y1=48, edge=False)
    det.note((0, 69), (-44, 64), "Crown at x = 0.70 L: Z1, projection, recorded")
    det.note((-19, 85), (-44, 80), "Webs, corners,\nfeet: Z1")
    det.note((-42, S - 0.5), (-38, 105), "Skin: Z1", ha="center")

    sec = Section(axs, **OVERVIEW, label="(b) Section through the burner axis", lw=2.0)
    sec.frame(aux=(0.5,))
    a3_specimen(sec)
    sec.stand_off()
    sec.box(-48, 48, 60, 108, "(c)")
    sec.note((200, S - 1), (240, 40), "Skin: zone of position", ha="left")
    sec.note((-200, S + 3), (-240, 130), "Skin far side: not impinged", ha="center")
    legend(fig, ())
    save(fig, "method_example_A3")


# A.4 Z-frame, inner cap toward the burner

CAP, T4 = 30.0, 2.0


def a4_specimen(sec, y0=-450, y1=450):
    cap_x, skin_x, t = S, 2 * S, T4
    sec.part(rect(y0, y1, skin_x, skin_x + t))
    sec.part(rect(-1, CAP, cap_x, cap_x + t))                # inner cap
    sec.part(rect(-1, 1, cap_x + t, skin_x - t))             # web
    sec.part(rect(-CAP, 1, skin_x - t, skin_x))              # outer flange
    sec.face([(-1, cap_x), (CAP, cap_x)], (0, -1))
    sec.face([(CAP, cap_x), (CAP, cap_x + t)], (1, 0))
    sec.face([(-1, cap_x), (-1, skin_x - t)], (-1, 0))
    sec.face([(-CAP, skin_x - t), (-1, skin_x - t)], (0, -1))
    sec.face([(-CAP, skin_x - t), (-CAP, skin_x)], (-1, 0))
    sec.face([(y0, skin_x), (-CAP, skin_x)], (0, -1))
    sec.face([(CAP, skin_x), (y1, skin_x)], (0, -1))
    # under the Z-return: K2, b = 30 mm
    sec.face([(1, cap_x + t), (CAP, cap_x + t)], (0, 1), cat="wrapped")
    sec.face([(1, cap_x + t), (1, skin_x)], (1, 0), cat="wrapped")
    sec.face([(1, skin_x), (CAP, skin_x)], (0, -1), cat="wrapped")
    sec.face([(y0, skin_x + t), (y1, skin_x + t)], (0, 1), cat="none")


def fig_a4():
    fig, axm, axd, axs = layout_big()
    m = ZoneMap(axm, 430, "(a) Zoning on the specimen, view from the burner")
    m.surface(rect(-450, 450, -450, 450))
    m.surface(rect(-1, CAP, -450, 450), z=3)
    m.surface(rect(-CAP, -1, -450, 450), z=3)
    m.contours()

    det = Section(axd, (-60, 70), (85, 215), "(c) Detail, frame on the axis", lw=3.0)
    det.frame(zone_labels=False, cone=False, aux=(2.0,))
    a4_specimen(det, y0=-60, y1=70)
    det.note((15, S - 1.5), (-55, 90), "Inner cap: Z1")
    det.note((-2.5, 150), (-55, 150), "Web side:\nZ1")
    det.note((2.5, 150), (25, 145), "Under the Z-return:\nhidden, Z1")

    sec = Section(axs, ylim=(-420, 420), xlim=(-80, 260), label="(b) Section through the burner axis",
                  lw=2.0)
    sec.frame(aux=(2.0,))
    a4_specimen(sec)
    sec.stand_off()
    sec.box(-60, 70, 85, 215, "(c)")
    sec.note((250, 2 * S - 1), (250, 120), "Skin: zone of position", ha="center")
    sec.note((-280, 2 * S + T4), (-300, 150), "Skin far side: not impinged", ha="center")
    legend(fig, ("wrapped",))
    save(fig, "method_example_A4")


# A.5 L-clip smaller than Z1

def fig_a5():
    fig, (axm, axs, axv) = layout_row()
    t = 2.0
    m = ZoneMap(axm, 230, "(a) Zoning on the clip, view from the burner")
    m.surface(rect(-25, 25, -20, 20), z=3)
    m.contours()
    m.note((25, 20), (110, 150), "Farthest corner r ≈ 32 mm: Z1", ha="center")

    sec = Section(axs, (-80, 80), (-40, 160), "(b) Section through the burner axis", lw=2.6)
    sec.frame(zone_labels=False, cone_label=False)
    sec.part(rect(-25, 25, S, S + t))
    sec.part(rect(25 - t, 25, S + t, S + 40))
    sec.face([(-25, S), (25, S)], (0, -1))
    sec.face([(-25, S), (-25, S + t)], (-1, 0))
    sec.face([(25, S), (25, S + 40)], (1, 0))
    sec.face([(-25, S + t), (25 - t, S + t)], (0, 1), cat="wrapped")
    sec.face([(25 - t, S + t), (25 - t, S + 40)], (-1, 0), cat="wrapped")
    sec.face([(25 - t, S + 40), (25, S + 40)], (0, 1), cat="wrapped")
    sec.note((0, S + t + 1.5), (-75, 150), "Back and inner faces:\nhidden, Z1")
    sec.stand_off(y=62)

    var = Section(axv, (-80, 80), (-40, 160), "(c) Variant: flat clip 80 × 60 mm", lw=2.6)
    var.frame(zone_labels=False, cone_label=False)
    var.part(rect(-30, 30, S, S + t))
    var.face([(-30, S), (30, S)], (0, -1))
    var.face([(-30, S), (-30, S + t)], (-1, 0))
    var.face([(30, S), (30, S + t)], (1, 0))
    var.face([(-30, S + t), (30, S + t)], (0, 1), cat="next")
    var.note((-10, S - 1.5), (-75, 60), "Front face and edges: Z1")
    var.note((10, S + t + 1.5), (-75, 140), "Back face:\nhidden, next zone Z2")
    legend(fig, ("wrapped", "next"))
    save(fig, "method_example_A5")


# A.6 Tube across the flame

def tube(sec, xc, R, rear_cat, t=1.5):
    ax = sec.ax
    ax.add_patch(Circle((0, xc), R, facecolor=PART, edgecolor=INK, lw=0.5, zorder=3))
    ax.add_patch(Circle((0, xc), R - t, facecolor="white", edgecolor=INK, lw=0.5, zorder=3))
    a = np.radians(np.linspace(-180, 180, 721))
    P = np.c_[R * np.sin(a), xc - R * np.cos(a)]
    N = np.c_[np.sin(a), -np.cos(a)]
    front = np.abs(np.degrees(a)) <= 100 + 1e-9
    sec.face(P[front], N[front])
    for part in (np.degrees(a) <= -100 + 1e-9, np.degrees(a) >= 100 - 1e-9):
        sec.face(P[part], N[part], cat=rear_cat)
    # theta = 100 deg limit
    th = np.radians(100)
    L = R + 14
    ax.plot([0, L * np.sin(th)], [xc, xc - L * np.cos(th)], color=INK, lw=0.5, zorder=6)
    ax.plot([0, 0], [xc, xc - L], color=INK, lw=0.5, zorder=6)
    ax.text(L * np.sin(th) + 2, xc - L * np.cos(th), "θ = 100°", fontsize=7, ha="left",
            va="center", zorder=6)


def fig_a6():
    fig, (axm, axs, axv) = layout_row()
    m = ZoneMap(axm, 430, "(a) Zoning on the tube, view from the burner")
    m.surface(rect(-450, 450, -12.5, 12.5), z=3)
    m.contours(section="v")
    m.note((150, 12), (200, 150), "Z2, full\ncircumference", ha="center")
    m.note((390, -12), (330, -150), "Beyond r = 350:\nnot impinged", ha="center")

    sec = Section(axs, (-80, 80), (-40, 205), "(b) Section normal to the tube", across="z", lw=2.6)
    sec.frame(zone_labels=False, cone_label=False)
    tube(sec, S + 12.5, 12.5, "wrapped")
    sec.note((-8, S + 3), (-75, 60), "θ ≤ 100°: Z1")
    sec.note((-6, S + 23), (-75, 178), "θ > 100°: hidden, Z1")
    sec.note((3, S + 12.5), (20, 150), "Inside: internal-side\nmodelling")
    sec.stand_off(y=62)

    var = Section(axv, (-80, 80), (-40, 205), "(c) Variant: duct Ø 60 mm", across="z", lw=2.6)
    var.frame(zone_labels=False, cone_label=False)
    tube(var, S + 30, 30.0, "next")
    var.note((-20, S + 8), (-75, 60), "θ ≤ 100°: Z1")
    var.note((-14, S + 57), (-75, 190), "θ > 100°: hidden,\nnext zone Z2")
    legend(fig, ("wrapped", "next"))
    save(fig, "method_example_A6")


# A.7 Firewall with bulkhead fitting

def hexagon(af):
    rc = af / np.sqrt(3)
    a = np.radians(np.arange(30, 390, 60))
    return np.c_[rc * np.cos(a), rc * np.sin(a)]


def a7_specimen(sec, y0=-450, y1=450, t=2.0):
    sec.part(rect(y0, y1, S, S + t))
    sec.part(rect(-15, 15, S - 30, S))
    sec.part(rect(-6, 6, S + t, S + 28))
    sec.part(rect(-15, 15, S + t, S + t + 13))
    sec.face([(y0, S), (-15, S)], (0, -1))
    sec.face([(15, S), (y1, S)], (0, -1))
    sec.face([(-15, S - 30), (15, S - 30)], (0, -1))
    sec.face([(-15, S - 30), (-15, S)], (-1, 0))
    sec.face([(15, S - 30), (15, S)], (1, 0))
    sec.face([(y0, S + t), (-15, S + t)], (0, 1), cat="none")
    sec.face([(15, S + t), (y1, S + t)], (0, 1), cat="none")
    for pts, n in (([(-15, S + t), (-15, S + t + 13)], (-1, 0)),
                   ([(15, S + t), (15, S + t + 13)], (1, 0)),
                   ([(-15, S + t + 13), (-6, S + t + 13)], (0, 1)),
                   ([(6, S + t + 13), (15, S + t + 13)], (0, 1)),
                   ([(-6, S + t + 13), (-6, S + 28)], (-1, 0)),
                   ([(6, S + t + 13), (6, S + 28)], (1, 0)),
                   ([(-6, S + 28), (6, S + 28)], (0, 1))):
        sec.face(pts, n, cat="none")


def fig_a7():
    fig, axm, axd, axs = layout_big()
    m = ZoneMap(axm, 430, "(a) Zoning on the specimen, view from the burner")
    m.surface(rect(-450, 450, -450, 450))
    m.surface(hexagon(30.0), z=3)
    m.contours()
    m.note((15, 0), (200, 385), "Hexagon 30 mm across flats: Z1", ha="center")

    det = Section(axd, (-48, 48), (60, 136), "(c) Detail, fitting on the axis", lw=3.0)
    det.frame(zone_labels=False, cone=False, aux=(0.7,))
    a7_specimen(det, y0=-48, y1=48)
    det.note((-15.5, 85), (-44, 78), "Hexagon, all faces:\nZ1")
    det.note((10, 69), (19, 84), "Front at x = 0.70 L:\nprojection, recorded")
    det.note((-35, S - 1), (-40, 94), "Firewall: Z1", ha="center")
    det.note((6.5, S + 22), (14, 125), "Back face:\nnot impinged")

    sec = Section(axs, **OVERVIEW, label="(b) Section through the burner axis", lw=2.0)
    sec.frame(aux=(0.5,))
    a7_specimen(sec)
    sec.stand_off()
    sec.box(-48, 48, 60, 136, "(c)")
    sec.note((250, S - 1), (250, 40), "Firewall flame side: zone of position", ha="center")
    sec.note((-250, S + 2), (-250, 130), "Back face: not impinged", ha="center")
    legend(fig, ())
    save(fig, "method_example_A7")


# A.8 Curved cowl, convex toward the burner

RC8 = 600.0


def x_cowl(r):
    return S + RC8 - np.sqrt(RC8 ** 2 - np.asarray(r) ** 2)


def a8_specimen(sec, ylim=(-420, 420), t=2.0):
    b = np.arcsin(np.linspace(ylim[0], ylim[1], 841) / RC8)
    P = np.c_[RC8 * np.sin(b), S + RC8 - RC8 * np.cos(b)]
    N = np.c_[np.sin(b), -np.cos(b)]
    sec.part(np.vstack([P, (P - t * N)[::-1]]))
    sec.face(P, N)
    sec.face(P - t * N, -N, cat="none")


def fig_a8():
    fig = plt.figure(figsize=(10.0, 5.2))
    axs = fig.add_axes([0.075, 0.2, 0.91, 0.74])
    sec = Section(axs, ylim=(-420, 420), xlim=(-80, 300), label="Section through the burner axis",
                  lw=2.0)
    sec.frame(aux=(2.0,))
    a8_specimen(sec)
    sec.stand_off()
    for r in (100.0, 200.0, 350.0):
        x = float(x_cowl(r))
        sec.ax.plot(r, x, marker="o", ms=3, color=INK, zorder=8)
        if r < 300:
            sec.text(r + 8, x - 6, f"r = {r:.0f}: x = {x:.0f}", va="top")
        else:
            sec.text(r - 10, x + 4, f"r = {r:.0f}: x = {x:.0f}", ha="right", va="bottom")
    sec.note((-200, float(x_cowl(200)) + 3), (-250, 260), "Back face: not impinged", ha="center")
    sec.note((-60, float(x_cowl(60)) - 3), (-200, 50), "Convex face:\nzone of position", ha="center")
    legend(fig, ())
    save(fig, "method_example_A8")


# Face rules of the default procedure, one panel per rule

RULES = dict(ylim=(-380, 380), xlim=(-45, 195))


def plate(sec, x, y0, y1, t=3.0, front="visible", back="none", gaps=()):
    sec.part(rect(y0, y1, x, x + t))
    a = y0
    for g0, g1 in sorted(gaps) + [(y1, y1)]:
        if g0 > a:
            sec.part(rect(a, g0, x, x + t))
            sec.face([(a, x), (g0, x)], (0, -1), cat=front)
            sec.face([(a, x + t), (g0, x + t)], (0, 1), cat=back)
        a = max(a, g1)


def fig_face_rules():
    fig = plt.figure(figsize=(10.0, 7.6))
    gs = fig.add_gridspec(3, 2, left=0.07, right=0.99, top=0.96, bottom=0.12, hspace=0.42, wspace=0.12)
    axs = [fig.add_subplot(gs[i, j]) for i in range(3) for j in range(2)]

    def panel(ax, label):
        sec = Section(ax, label=label, lw=2.2, **RULES)
        sec.frame(ref=False, cone_label=False)
        return sec

    # (a) visible faces
    sec = panel(axs[0], "(a) Visible faces: zone of position")
    sec.part(rect(-370, 370, S, S + 3))
    sec.part(rect(120, 200, S - 30, S))
    sec.face([(-370, S), (120, S)], (0, -1))
    sec.face([(200, S), (370, S)], (0, -1))
    sec.face([(120, S - 30), (200, S - 30)], (0, -1))
    sec.face([(120, S - 30), (120, S)], (-1, 0))
    sec.face([(200, S - 30), (200, S)], (1, 0))
    sec.face([(-370, S + 3), (370, S + 3)], (0, 1), cat="none")

    # (b) part narrower than 0.25 D1
    sec = panel(axs[1], "(b) Part narrower than 0.25 D1: hidden faces, zone of position")
    sec.part(rect(-12.5, 12.5, S - 10, S - 7))
    sec.face([(-12.5, S - 10), (12.5, S - 10)], (0, -1))
    sec.face([(-12.5, S - 7), (12.5, S - 7)], (0, 1), cat="wrapped")
    sec.part(rect(-370, 370, S + 30, S + 33))
    sec.face([(-370, S + 30), (-12.5, S + 30)], (0, -1))
    sec.face([(-12.5, S + 30), (12.5, S + 30)], (0, -1), cat="wrapped")
    sec.face([(12.5, S + 30), (370, S + 30)], (0, -1))
    sec.face([(-370, S + 33), (370, S + 33)], (0, 1), cat="none")
    sec.note((-12, S - 8), (-360, 35), "part b = 25 mm,\nplate 37 mm behind (> b)")

    # (c) part 0.25 D1 to D1 wide
    sec = panel(axs[2], "(c) Part 0.25 D1 to D1 wide: hidden faces, next zone")
    sec.part(rect(-40, 40, S, S + 3))
    sec.face([(-40, S), (40, S)], (0, -1))
    sec.face([(-40, S + 3), (40, S + 3)], (0, 1), cat="next")
    sec.part(rect(-370, 370, S + 50, S + 53))
    sec.face([(-370, S + 50), (-40, S + 50)], (0, -1))
    sec.face([(-40, S + 50), (40, S + 50)], (0, -1), cat="next")
    sec.face([(40, S + 50), (370, S + 50)], (0, -1))
    sec.face([(-370, S + 53), (370, S + 53)], (0, 1), cat="none")
    sec.note((-40, S + 2), (-360, 35), "part b = 80 mm,\nplate 47 mm behind (< b)")

    # (d) wall without opening
    sec = panel(axs[3], "(d) Wall, no opening inside Z1: faces behind not reached")
    plate(sec, S, -370, 370)
    sec.part(rect(-1, 1, S + 3, S + 50))
    sec.face([(-1, S + 3), (-1, S + 50)], (-1, 0), cat="none")
    sec.face([(1, S + 3), (1, S + 50)], (1, 0), cat="none")

    # (e) wall with an opening inside Z1
    sec = panel(axs[4], "(e) Wall, opening inside Z1: faces behind, next zone")
    plate(sec, S, -370, 370, back="next", gaps=[(-30, 30)])
    sec.face([(-30, S), (-30, S + 3)], (1, 0))
    sec.face([(30, S), (30, S + 3)], (-1, 0))
    sec.part(rect(-370, 370, S + 50, S + 53))
    sec.face([(-370, S + 50), (-30, S + 50)], (0, -1), cat="next")
    sec.face([(-30, S + 50), (30, S + 50)], (0, -1))
    sec.face([(30, S + 50), (370, S + 50)], (0, -1), cat="next")
    sec.face([(-370, S + 53), (370, S + 53)], (0, 1), cat="none")
    sec.note((0, S + 49), (-360, 35), "visible through the opening:\nzone of position")

    # (f) cavity
    sec = panel(axs[5], "(f) Cavity, 0.25 w ≤ d ≤ w: zone of the opening")
    y0, y1, dd, t = 70.0, 130.0, 40.0, 3.0
    plate(sec, S, -370, 370, gaps=[(y0, y1)])
    sec.part(rect(y0 - t, y0, S, S + dd + t))
    sec.part(rect(y1, y1 + t, S, S + dd + t))
    sec.part(rect(y0 - t, y1 + t, S + dd, S + dd + t))
    sec.face([(y0, S), (y0, S + dd)], (1, 0), cat="wrapped", zone="Z1")
    sec.face([(y1, S), (y1, S + dd)], (-1, 0), cat="wrapped", zone="Z1")
    sec.face([(y0, S + dd), (y1, S + dd)], (0, -1), cat="wrapped", zone="Z1")
    sec.face([(y0 - t, S + 3), (y0 - t, S + dd + t)], (-1, 0), cat="none")
    sec.face([(y1 + t, S + 3), (y1 + t, S + dd + t)], (1, 0), cat="none")
    sec.face([(y0 - t, S + dd + t), (y1 + t, S + dd + t)], (0, 1), cat="none")
    sec.note((y0, S + 30), (-360, 35), "opening crosses Z1 and Z2:\nall internal faces Z1")

    for ax in axs:
        ax.set_xlabel("")
        ax.set_ylabel("x (mm)", fontsize=8)
    legend(fig, ("wrapped", "next"))
    save(fig, "method_face_rules")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    fig_face_rules()
    for f in (fig_a1, fig_a2, fig_a3, fig_a4, fig_a5, fig_a6, fig_a7, fig_a8):
        f()
