"""Figures for fire_thermal_analysis_method.md. Output: ../images/method_*.png (200 dpi) and .svg."""
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patheffects as pe
import numpy as np
from matplotlib.patches import Circle, FancyArrowPatch, Polygon, Rectangle

OUT = Path(__file__).resolve().parent.parent / "images"

Z1, Z2, Z3 = "#E8862A", "#F2D13D", "#7BAF4A"
ZC = {"Z1": Z1, "Z2": Z2, "Z3": Z3}
INK = "black"
GREY = "#606060"
LIGHT = "#B0B0B0"

plt.rcParams.update({
    "font.family": "sans-serif",
    "font.sans-serif": ["Arial", "DejaVu Sans"],
    "font.size": 9,
    "axes.linewidth": 0.8,
    "axes.edgecolor": INK,
    "savefig.facecolor": "white",
    "figure.facecolor": "white",
    "svg.fonttype": "path",
    "mathtext.default": "regular",
})


def save(fig, name):
    for ext, kw in (("png", {"dpi": 200}), ("svg", {})):
        path = OUT / f"{name}.{ext}"
        fig.savefig(path, bbox_inches="tight", pad_inches=0.05, **kw)
    plt.close(fig)


def dim_line(ax, x0, y0, x1, y1, text, offset=(0, 0), ha="center", va="bottom", fs=8, rotation=0):
    ax.annotate("", xy=(x1, y1), xytext=(x0, y0),
                arrowprops=dict(arrowstyle="<|-|>", lw=0.7, color=INK, mutation_scale=7,
                                shrinkA=0, shrinkB=0))
    ax.text((x0 + x1) / 2 + offset[0], (y0 + y1) / 2 + offset[1], text,
            ha=ha, va=va, fontsize=fs, rotation=rotation)


def ext_line(ax, x0, y0, x1, y1):
    ax.plot([x0, x1], [y0, y1], color=GREY, lw=0.5)


# Figure 1: zone layout

def fig_zones():
    fig, axes = plt.subplots(1, 2, figsize=(10, 5.6))
    cases = (
        (axes[0], 0.0, "(a) Vertical burner axis", "x (mm)", "y (mm)"),
        (axes[1], 100.0, "(b) Horizontal burner axis", "Horizontal (mm)", "Vertical (mm)"),
    )
    for ax, z3_dy, label, xl, yl in cases:
        centres = {"Z3": (0.0, z3_dy), "Z2": (0.0, 0.0), "Z1": (0.0, 0.0)}
        radii = {"Z3": 350.0, "Z2": 200.0, "Z1": 100.0}
        for z in ("Z3", "Z2", "Z1"):
            ax.add_patch(Circle(centres[z], radii[z], facecolor=ZC[z], edgecolor=INK, lw=0.9))
        ax.text(0, -50, "Z1", ha="center", va="center", fontsize=9)
        ax.text(0, -150, "Z2", ha="center", va="center", fontsize=9)
        ax.text(0, z3_dy + 290, "Z3", ha="center", va="center", fontsize=9)

        # burner axis point
        ax.plot([-15, 15], [0, 0], color=INK, lw=0.9)
        ax.plot([0, 0], [-15, 15], color=INK, lw=0.9)
        ax.annotate("Burner axis", xy=(5, -5), xytext=(370, -120), fontsize=8,
                    arrowprops=dict(arrowstyle="-", lw=0.6, color=INK, shrinkA=0, shrinkB=0))

        # diameter dimensions below the circles
        for z, ydim in (("Z1", -415.0), ("Z2", -470.0), ("Z3", -525.0)):
            cx, cy = centres[z]
            r = radii[z]
            ext_line(ax, cx - r, cy, cx - r, ydim - 10)
            ext_line(ax, cx + r, cy, cx + r, ydim - 10)
            dim_line(ax, cx - r, ydim, cx + r, ydim, f"Ø{int(2 * r)}", offset=(0, 3))

        if z3_dy:
            ax.plot([-15, 15], [z3_dy, z3_dy], color=INK, lw=0.6)
            ax.plot([0, 0], [z3_dy - 15, z3_dy + 15], color=INK, lw=0.6)
            ax.annotate("Z3 centre", xy=(5, z3_dy + 5), xytext=(370, z3_dy + 60), fontsize=8,
                        arrowprops=dict(arrowstyle="-", lw=0.6, color=INK, shrinkA=0, shrinkB=0))
            ext_line(ax, -15, 0, -265, 0)
            ext_line(ax, -15, z3_dy, -265, z3_dy)
            dim_line(ax, -250, 0, -250, z3_dy, "100", offset=(-8, 0), ha="right", va="center")
            ax.annotate("", xy=(430, 330), xytext=(430, 200),
                        arrowprops=dict(arrowstyle="-|>", lw=1.0, color=INK, mutation_scale=10))
            ax.text(445, 265, "Up", ha="left", va="center", fontsize=9)

        ax.set_aspect("equal")
        ax.set_xlim(-480, 540)
        ax.set_ylim(-575, 480)
        ax.set_xticks(np.arange(-400, 401, 200))
        ax.set_yticks(np.arange(-400, 401, 200))
        ax.set_xlabel(xl)
        ax.set_ylabel(yl)
        ax.tick_params(labelsize=8)
        ax.text(0.5, -0.17, label, transform=ax.transAxes, ha="center", va="top", fontsize=9)
    fig.subplots_adjust(wspace=0.25)
    save(fig, "method_zones")


# Figure 2: net flux versus wall temperature

SIGMA = 5.67e-8
T_FLAME = 1373.0
VF = {"Z1": 0.38, "Z2": 0.19, "Z3": 0.095}
HTC = {"Z1": 37.0, "Z2": 18.5, "Z3": 9.25}


def flux_parts(zone, tw_c, eps=1.0):
    tw = tw_c + 273.15
    rad = VF[zone] * eps * SIGMA * (T_FLAME ** 4 - tw ** 4)
    conv = HTC[zone] * (T_FLAME - tw)
    return rad / 1e3, conv / 1e3


def fig_flux():
    tw = np.linspace(20.0, 1100.0, 541)
    fig, ax = plt.subplots(figsize=(6.4, 4.2))
    outline = [pe.Stroke(linewidth=3.0, foreground="#404040"), pe.Normal()]
    for z in ("Z1", "Z2", "Z3"):
        rad, conv = flux_parts(z, tw)
        ax.plot(tw, rad + conv, color=ZC[z], lw=2.0, path_effects=outline, label=z)
    rad, conv = flux_parts("Z1", tw)
    ax.plot(tw, rad, color=GREY, lw=0.8, ls="--", label="Z1 radiative part")
    ax.plot(tw, conv, color=GREY, lw=0.8, ls=(0, (1.5, 1.5)), label="Z1 convective part")
    ax.errorbar([20.0], [116.0], yerr=[10.0], fmt="none", ecolor=INK, elinewidth=1.0, capsize=4,
                label="ISO 2685 calibration 116 ± 10 kW/m²")
    ax.set_xlim(0, 1120)
    ax.set_ylim(0, 130)
    ax.set_xticks(np.arange(0, 1101, 100))
    ax.set_xlabel(r"Wall temperature $T_{wall}$ (°C)")
    ax.set_ylabel(r"Net flux density $\varphi_i$ (kW/m²)")
    ax.grid(True, color="#DDDDDD", lw=0.5)
    ax.set_axisbelow(True)
    ax.legend(frameon=False, fontsize=8, loc="upper right")
    save(fig, "method_flux")
    return {z: sum(flux_parts(z, 20.0)) for z in ("Z1", "Z2", "Z3")}


# Appendix F Part VII model identified at 710 mm stand-off: one reference temperature per zone
P7_TREF = {"Z1": 750.0 + 273.15, "Z2": 650.0 + 273.15}
P7_VF = {"Z1": 0.6, "Z2": 0.3}
P7_HTC = {"Z1": 10.0, "Z2": 5.0}


def flux_p7(zone, tw_c):
    tw = np.asarray(tw_c) + 273.15
    tr = P7_TREF[zone]
    return (P7_VF[zone] * SIGMA * (tr ** 4 - tw ** 4) + P7_HTC[zone] * (tr - tw)) / 1e3


def fig_flux_part7():
    tw = np.linspace(20.0, 800.0, 391)
    fig, ax = plt.subplots(figsize=(6.4, 4.2))
    outline = [pe.Stroke(linewidth=3.0, foreground="#404040"), pe.Normal()]
    for z in ("Z1", "Z2"):
        ax.plot(tw, flux_p7(z, tw), color=ZC[z], lw=2.0, path_effects=outline,
                label=f"{z}, T_ref = {P7_TREF[z] - 273.15:.0f} °C")
    ax.axhline(0.0, color=INK, lw=0.6)
    ax.set_xlim(0, 820)
    ax.set_ylim(-10, 50)
    ax.set_xticks(np.arange(0, 801, 100))
    ax.set_xlabel(r"Wall temperature $T_{wall}$ (°C)")
    ax.set_ylabel(r"Net flux density $\varphi_i$ (kW/m²)")
    ax.grid(True, color="#DDDDDD", lw=0.5)
    ax.set_axisbelow(True)
    ax.legend(frameon=False, fontsize=8, loc="upper right")
    save(fig, "method_flux_partVII")
    return {z: [round(float(flux_p7(z, t)), 1) for t in (20.0, 400.0, 600.0, 650.0, 750.0)]
            for z in ("Z1", "Z2")}


# Shared box drawing for figures 3 and 4

def box(ax, x, y, w, h, text, lw=0.8, fs=8.5, fc="white", ls="-"):
    ax.add_patch(Rectangle((x, y), w, h, facecolor=fc, edgecolor=INK, lw=lw, ls=ls))
    ax.text(x + w / 2, y + h / 2, text, ha="center", va="center", fontsize=fs, linespacing=1.25)


def arrow(ax, x0, y0, x1, y1, ls="-", lw=0.9, style="-|>"):
    ax.add_patch(FancyArrowPatch((x0, y0), (x1, y1), arrowstyle=style, mutation_scale=10,
                                 lw=lw, color=INK, linestyle=ls, shrinkA=0, shrinkB=0))


# Figure 3: process

def fig_process():
    fig, ax = plt.subplots(figsize=(11, 5.0))
    ax.set_xlim(0, 110)
    ax.set_ylim(0, 50)
    ax.axis("off")

    steps = [
        ("1. Define\nfire scenario", "Fire risk lead\n+ stress + thermal\nspecialists"),
        ("2. Calculate\nstructure temperature", "Thermal specialist"),
        ("3. Calculate\nstress fields", "Stress specialist"),
        ("4. Design\njustification", "Fire risk lead"),
    ]
    w, h, gap, x0, y0 = 22.0, 9.0, 6.0, 1.5, 38.0
    for i, (txt, role) in enumerate(steps):
        x = x0 + i * (w + gap)
        box(ax, x, y0, w, h, txt, lw=2.4 if i == 1 else 0.8, fs=9)
        ax.text(x + w / 2, y0 - 1.2, role, ha="center", va="top", fontsize=8, color=GREY,
                linespacing=1.2)
        if i < len(steps) - 1:
            arrow(ax, x + w, y0 + h / 2, x + w + gap, y0 + h / 2)

    tasks = [
        "Alignment\nmeeting",
        "Zoning:\nprojection /\nescalation level",
        "Model\nglobal / local,\ninitial field,\nToO",
        "Fire run,\nexposure duration",
        "Checks and\nsensitivities",
        "Results at\nexposure times\nto stress",
    ]
    tw, th, tgap, tx0, ty0 = 15.5, 13.0, 2.9, 1.5, 4.0
    frame_x0, frame_x1 = tx0 - 1.0, tx0 + 6 * tw + 5 * tgap + 1.0
    ax.add_patch(Rectangle((frame_x0, ty0 - 2.0), frame_x1 - frame_x0, th + 7.5,
                           facecolor="none", edgecolor=LIGHT, lw=0.8, ls="--"))
    ax.text(frame_x0 + 1.0, ty0 + th + 4.6, "Step 2: thermal analysis tasks", ha="left",
            va="center", fontsize=8.5, color=GREY)
    for i, txt in enumerate(tasks):
        x = tx0 + i * (tw + tgap)
        box(ax, x, ty0, tw, th, txt, fs=8)
        if i < len(tasks) - 1:
            arrow(ax, x + tw, ty0 + th / 2, x + tw + tgap, ty0 + th / 2)

    s2_xc = x0 + (w + gap) + w / 2
    arrow(ax, s2_xc, 33.5, s2_xc, ty0 + th + 5.5, ls="--", lw=0.8)
    save(fig, "method_process")


# Figure 4: global and local model process

def fig_global_local():
    fig, ax = plt.subplots(figsize=(8.6, 5.4))
    ax.set_xlim(0, 100)
    ax.set_ylim(0, 60)
    ax.axis("off")

    w, h = 24.0, 9.0
    gx, lx = 20.0, 74.0
    y_noc, y_fire = 40.0, 18.0
    ax.text(gx + w / 2, 55, "Global model", ha="center", va="center", fontsize=10, weight="bold")
    ax.text(lx + w / 2, 55, "Local model", ha="center", va="center", fontsize=10, weight="bold")
    ax.text(1, y_noc + h / 2, "Initialisation", ha="left", va="center", fontsize=9.5, weight="bold")
    ax.text(1, y_fire + h / 2, "Fire calculation", ha="left", va="center", fontsize=9.5,
            weight="bold")
    ax.plot([1, 99], [33.5, 33.5], color=LIGHT, lw=0.6)

    box(ax, gx, y_noc, w, h, "NOC calculation", fs=9)
    box(ax, lx, y_noc, w, h, "NOC calculation", fs=9)
    box(ax, gx, y_fire, w, h, "Fire calculation,\nexposure duration", fs=9)
    box(ax, lx, y_fire, w, h, "Fire calculation,\nexposure duration", fs=9)

    # global NOC -> local NOC, and check back
    arrow(ax, gx + w, y_noc + 6.0, lx, y_noc + 6.0)
    ax.text((gx + w + lx) / 2, y_noc + 6.8, "cut BCs from global NOC", ha="center", va="bottom",
            fontsize=8)
    arrow(ax, lx, y_noc + 3.0, gx + w, y_noc + 3.0, ls="--", lw=0.8)
    ax.text((gx + w + lx) / 2, y_noc + 2.2, "check", ha="center", va="top", fontsize=8)

    # global fire -> local fire
    arrow(ax, gx + w, y_fire + h / 2, lx, y_fire + h / 2)
    ax.text((gx + w + lx) / 2, y_fire + h / 2 + 1.0, "cut BCs updated from global fire,\n"
            "non-impinged boundaries", ha="center", va="bottom", fontsize=8, linespacing=1.2)

    # NOC -> fire in each column
    for x in (gx, lx):
        arrow(ax, x + w / 2, y_noc, x + w / 2, y_fire + h)

    box(ax, 20.0, 3.0, 78.0, 7.0,
        "Ambient BCs on non-impinged surfaces: convective ambient T, radiative environment",
        fs=8.5, fc="#F2F2F2")
    save(fig, "method_global_local")


# Figure 5: cylindrical projection

def zone_of(r):
    if r <= 100:
        return "Z1"
    if r <= 200:
        return "Z2"
    if r <= 350:
        return "Z3"
    return None


def coloured_segments(ax, xs, ys, rs, lw):
    for i in range(len(xs) - 1):
        z = zone_of(0.5 * (rs[i] + rs[i + 1]))
        ax.plot(xs[i:i + 2], ys[i:i + 2], color=ZC[z] if z else LIGHT, lw=lw,
                solid_capstyle="butt")


def fig_projection():
    fig, (ax, ax2) = plt.subplots(1, 2, figsize=(9.6, 6.2),
                                  gridspec_kw={"width_ratios": [1.67, 1.0]})
    x_max = 530

    # burner cone, exit at x = 0
    ax.add_patch(Polygon([(-160, -55), (0, -38), (0, 38), (-160, 55)], closed=True,
                         facecolor="#D9D9D9", edgecolor=INK, lw=0.8))
    ax.text(-80, -62, "Burner cone", ha="center", va="top", fontsize=8)
    ax.plot([-180, x_max], [0, 0], color=INK, lw=0.6, ls=(0, (8, 3, 1, 3)))
    ax.text(x_max - 5, -8, "Burner axis", ha="right", va="top", fontsize=8)

    # reference plane and stand-off
    ax.plot([100, 100], [-400, 400], color=GREY, lw=0.8)
    ax.text(104, 395, "Reference plane", ha="left", va="top", fontsize=8, color=GREY)
    dim_line(ax, 0, 70, 100, 70, "100", offset=(-22, 3))
    ext_line(ax, 0, 38, 0, 80)

    # zone boundaries extruded parallel to the axis
    for r in (100, 200, 350):
        for s in (1, -1):
            ax.plot([0, x_max], [s * r, s * r], color=GREY, lw=0.7, ls="--")
        ax.text(-8, r, f"r = {r}", ha="right", va="center", fontsize=7.5)
    for z, yc in (("Z1", 50), ("Z2", 150), ("Z3", 275)):
        for s in (1, -1):
            ax.text(x_max - 8, s * yc, z, ha="right", va="center", fontsize=8.5)

    # impinged plate at 45 deg through the reference point on the axis: x = 100 + r
    yp = np.linspace(-50, 350, 401)
    xp = 100.0 + yp
    coloured_segments(ax, xp, yp, np.abs(yp), lw=5.0)
    ax.plot(xp, yp, color=INK, lw=0.4)

    # inclination angle between plate and axis
    th = np.radians(np.linspace(0, 45, 30))
    ax.plot(100 + 70 * np.cos(th), 70 * np.sin(th), color=INK, lw=0.6)
    ax.text(178, 22, "45°", ha="left", va="center", fontsize=8)

    ax.annotate("Zone boundary extruded\nparallel to burner axis", xy=(320, -200),
                xytext=(270, -290), fontsize=8, ha="left", va="center",
                arrowprops=dict(arrowstyle="-", lw=0.6, color=INK, shrinkA=2, shrinkB=0))
    ax.text(440, 372, "Impinged plate", ha="right", va="center", fontsize=8)

    ax.set_aspect("equal")
    ax.set_xlim(-200, x_max)
    ax.set_ylim(-420, 420)
    ax.set_xlabel("Distance from burner cone exit along axis (mm)")
    ax.set_ylabel("Radial position r (mm)")
    ax.tick_params(labelsize=8)
    ax.text(0.5, -0.16, "(a) Side view", transform=ax.transAxes, ha="center", va="top")

    # panel (b): skin at reference plane, Z-stringer on the axis toward the burner
    ax2.plot([-15, 105], [0, 0], color=INK, lw=0.6, ls=(0, (8, 3, 1, 3)))
    ax2.add_patch(Polygon([(-15, -22), (0, -16), (0, 16), (-15, 22)], closed=True,
                          facecolor="#D9D9D9", edgecolor=INK, lw=0.8))
    for r in (100,):
        for s in (1, -1):
            ax2.plot([0, 105], [s * r, s * r], color=GREY, lw=0.7, ls="--")
    skin_x, t = 100.0, 3.0
    flange_x, flange_t = 75.0, 2.0
    ax2.add_patch(Rectangle((skin_x, -160), t, 320, facecolor="#E6E6E6", edgecolor=INK, lw=0.6))
    # Z-stringer: attached flange y in [-20, 0], web along the axis, free flange y in [0, 20]
    ax2.add_patch(Rectangle((skin_x - flange_t, -20), flange_t, 20, facecolor="#E6E6E6",
                            edgecolor=INK, lw=0.6))
    ax2.add_patch(Rectangle((flange_x, -1), skin_x - flange_t - flange_x, 2, facecolor="#E6E6E6",
                            edgecolor=INK, lw=0.6))
    ax2.add_patch(Rectangle((flange_x, 0), flange_t, 20, facecolor="#E6E6E6", edgecolor=INK,
                            lw=0.6))

    face_lw = 2.2
    # skin flame-side face, outside the stringer footprint
    ys = np.linspace(-160, -20, 141)
    coloured_segments(ax2, np.full_like(ys, skin_x - 1.5), ys, np.abs(ys), face_lw)
    ys = np.linspace(20, 160, 141)
    coloured_segments(ax2, np.full_like(ys, skin_x - 1.5), ys, np.abs(ys), face_lw)
    # stringer faces seen from the burner side, all in Z1
    ax2.plot([flange_x - 1.5] * 2, [0, 20], color=Z1, lw=face_lw, solid_capstyle="butt")
    ax2.plot([skin_x - flange_t - 1.5] * 2, [-20, -1], color=Z1, lw=face_lw, solid_capstyle="butt")
    ax2.plot([flange_x + 2, skin_x - flange_t], [-2.5, -2.5], color=Z1, lw=face_lw,
             solid_capstyle="butt")
    # shadowed skin strip and free-flange underside, zone of position
    for xf in (skin_x - 1.5, flange_x + flange_t + 1.5):
        ax2.plot([xf] * 2, [1.5, 20], color=Z1, lw=face_lw, solid_capstyle="butt")
        ax2.plot([xf] * 2, [1.5, 20], color=INK, lw=0.8, ls=(0, (1, 1.5)))
    ax2.text(95, 117, "Skin", ha="right", va="top", fontsize=8)
    ax2.annotate("Z-stringer", xy=(85, -2), xytext=(45, -45), fontsize=8, ha="center",
                 va="center", arrowprops=dict(arrowstyle="-", lw=0.6, color=INK, shrinkA=2,
                                              shrinkB=0))
    for z, yc in (("Z1", 60), ("Z2", 111)):
        for s in (1, -1):
            ax2.text(9, s * yc, z, ha="left", va="center", fontsize=8.5)
    ax2.set_aspect("equal")
    ax2.set_xlim(-15, 110)
    ax2.set_ylim(-120, 120)
    ax2.set_xticks([0, 50, 100])
    ax2.set_xlabel("Distance from cone exit (mm)")
    ax2.tick_params(labelsize=8)
    ax2.text(0.5, -0.16, "(b) Stiffened skin, stringer\non the axis", transform=ax2.transAxes,
             ha="center", va="top")
    fig.subplots_adjust(wspace=0.15)
    save(fig, "method_projection")


# Rig schematics (side views, mm)

LEADER = dict(arrowstyle="-", lw=0.6, color=INK, shrinkA=2, shrinkB=0)


def gravity(ax, x, y, length):
    ax.annotate("", xy=(x, y - length), xytext=(x, y),
                arrowprops=dict(arrowstyle="-|>", lw=1.0, color=INK, mutation_scale=10))
    ax.text(x + 15, y - length / 2, "g", ha="left", va="center", fontsize=9, style="italic")


def fig_rig_part7():
    # local frame: u along burner axis from cone exit centre, v in the frame plane (upward)
    a = np.radians(30.0)
    eu = np.array([np.cos(a), np.sin(a)])
    ev = np.array([-np.sin(a), np.cos(a)])

    def T(u, v):
        p = np.outer(np.atleast_1d(u), eu) + np.outer(np.atleast_1d(v), ev)
        return p if p.shape[0] > 1 else p[0]

    def poly(ax, uv, **kw):
        ax.add_patch(Polygon([T(u, v) for u, v in uv], closed=True, **kw))

    u_cal, u_gauge, v_end = 102.0, 102.0 + 305.0, 850.0
    st_depth, bl0, bl1 = 25.0, 27.0, 100.0

    def draw(ax, detail=False):
        # burner body and cone (305 mm long, exit 152 mm in the drawing plane)
        poly(ax, [(-505, -110), (-305, -110), (-305, 110), (-505, 110)],
             facecolor="#EFEFEF", edgecolor=INK, lw=0.8)
        poly(ax, [(-305, -60), (0, -76), (0, 76), (-305, 60)],
             facecolor="#D9D9D9", edgecolor=INK, lw=0.8)
        ax.plot(*T([-560, 620], [0, 0]).T, color=INK, lw=0.6, ls=(0, (8, 3, 1, 3)))
        # calibration plane
        ax.plot(*T([u_cal, u_cal], [-v_end - 60, v_end + 60]).T, color=GREY, lw=0.8,
                ls=(0, (6, 2, 1, 2)))
        if detail:
            # calibration rig: no specimen
            return
        # insulation blanket inboard of the stringers (thickness schematic)
        poly(ax, [(u_cal + bl0, -v_end), (u_cal + bl1, -v_end), (u_cal + bl1, v_end),
                  (u_cal + bl0, v_end)], facecolor="#F4F4F4", edgecolor=GREY, lw=0.7,
             hatch="....")
        # horizontal stringers in section (spacing and depth schematic)
        for vs in (-635, -381, -127, 127, 381, 635):
            poly(ax, [(u_cal, vs - 8), (u_cal + st_depth, vs - 8), (u_cal + st_depth, vs + 8),
                      (u_cal, vs + 8)], facecolor="#505050", edgecolor=INK, lw=0.5)
        # cold-side gauge on the burner centreline plane
        poly(ax, [(u_gauge, -22), (u_gauge + 30, -22), (u_gauge + 30, 22), (u_gauge, 22)],
             facecolor="white", edgecolor=INK, lw=0.8)

    fig, ax = plt.subplots(figsize=(8.2, 8.0))
    draw(ax)

    # dimensions
    for v0 in (-76.0,):
        ext_line(ax, *T(0, v0), *T(0, -175))
    ext_line(ax, *T(u_cal, -v_end - 60), *T(u_cal, -175))
    p0, p1 = T(0, -160), T(u_cal, -160)
    dim_line(ax, *p0, *p1, "102", offset=(14, -24), ha="center", va="center", rotation=30)
    for v0 in (-76.0, 76.0):
        ext_line(ax, *T(0, v0), *T(50, v0))
    p0, p1 = T(40, -76), T(40, 76)
    dim_line(ax, *p0, *p1, "", offset=(0, 0))
    ax.text(*T(40, 125), "152", ha="center", va="center", fontsize=8, rotation=-60)
    ext_line(ax, *T(u_gauge, 22), *T(u_gauge, 175))
    p0, p1 = T(u_cal, 160), T(u_gauge, 160)
    dim_line(ax, *p0, *p1, "305", offset=(-8, 14), ha="center", va="center", rotation=30)

    # frame 30 deg to vertical, measured at the top end on the outboard side
    b = T(u_cal, v_end + 60)
    ax.plot([b[0], b[0]], [b[1], b[1] - 330], color=GREY, lw=0.6, ls="--")
    th = np.radians(np.linspace(270, 300, 30))
    ax.plot(b[0] + 260 * np.cos(th), b[1] + 260 * np.sin(th), color=INK, lw=0.6)
    ax.text(b[0] - 15, b[1] - 230, "30°", ha="right", va="center", fontsize=8)
    # burner axis 30 deg to horizontal
    g = T(u_gauge + 30, 0)
    ax.plot([g[0], g[0] + 180], [g[1], g[1]], color=GREY, lw=0.6, ls="--")
    th = np.radians(np.linspace(0, 30, 30))
    ax.plot(g[0] + 160 * np.cos(th), g[1] + 160 * np.sin(th), color=INK, lw=0.6)
    ax.text(g[0] + 170, g[1] + 40, "30°", ha="left", va="center", fontsize=8)
    # right angle between axis and frame plane
    sq = np.array([T(u_cal - 30, 0), T(u_cal - 30, 30), T(u_cal, 30)])
    ax.plot(sq[:, 0], sq[:, 1], color=INK, lw=0.5)

    # labels
    ax.annotate("calibration plane\n(stringer outer surface)", xy=T(u_cal, 440),
                xytext=(-600, 470), fontsize=8, ha="left", va="center", arrowprops=LEADER)
    ax.annotate("horizontal stringers (section)", xy=T(u_cal + 12, -381),
                xytext=(620, -360), fontsize=8, ha="left", va="center", arrowprops=LEADER)
    ax.annotate("insulation blanket", xy=T(u_cal + 65, -560), xytext=(620, -520),
                fontsize=8, ha="left", va="center", arrowprops=LEADER)
    ax.annotate("cold-side gauge,\n305 mm from frame face", xy=T(u_gauge + 15, 22),
                xytext=(120, 620), fontsize=8, ha="left", va="center", arrowprops=LEADER)
    ax.annotate("burner cone,\nexit 152 mm × 280 mm", xy=T(-150, 70), xytext=(-600, 60),
                fontsize=8, ha="left", va="center", arrowprops=LEADER)
    ax.annotate("burner axis, normal\nto frame plane", xy=T(620, 0), xytext=(640, 440),
                fontsize=8, ha="left", va="center", arrowprops=LEADER)
    ax.text(-600, 330, "Outboard\n(burner side)", ha="left", va="center", fontsize=8.5,
            color=GREY)
    ax.text(620, 30, "Inboard\n(cold side)", ha="left", va="center", fontsize=8.5,
            color=GREY)
    gravity(ax, 900, 820, 180)

    # calibration inset: gauge position in the calibration plane, same orientation
    ins = ax.inset_axes([-600, -820, 560, 340], transform=ax.transData)
    draw(ins, detail=True)
    gc = T(u_cal, 25.4)
    poly(ins, [(u_cal, 25.4 - 14), (u_cal + 14, 25.4 - 14), (u_cal + 14, 25.4 + 14),
               (u_cal, 25.4 + 14)], facecolor="white", edgecolor=INK, lw=0.8)
    ins.plot(*gc, marker="+", color=INK, ms=7, mew=0.8)
    ext_line(ins, *T(u_cal, 25.4), *T(u_cal - 60, 25.4))
    p0, p1 = T(u_cal - 50, 0), T(u_cal - 50, 25.4)
    dim_line(ins, *p0, *p1, "", offset=(0, 0))
    ins.text(*T(u_cal - 62, 12.7), "25.4", ha="right", va="center", fontsize=8)
    ctr = T(u_cal - 20, 5)
    ins.set_xlim(ctr[0] - 110, ctr[0] + 110)
    ins.set_ylim(ctr[1] - 67, ctr[1] + 67)
    ins.set_aspect("equal")
    ins.set_xticks([])
    ins.set_yticks([])
    ax.text(-600, -835, "calibration (separate rig): gauge centre\n25.4 mm above burner centreline,\n102 mm from the cone exit plane",
            ha="left", va="top", fontsize=8)

    ax.set_aspect("equal")
    ax.set_xlim(-620, 1000)
    ax.set_ylim(-1020, 900)
    ax.axis("off")
    ax.text(1000, -1020, "Dimensions in mm. Stringer spacing, stringer depth and blanket "
            "thickness schematic.", ha="right", va="bottom", fontsize=7.5, color=GREY)
    save(fig, "method_rig_partVII")


def fig_rig_part3():
    fig, ax = plt.subplots(figsize=(7.6, 7.2))
    half, wall_x, ceil_y, tp = 75.0, 125.0, 200.0, 8.0

    # burner body and extension cone, exit at y = 0
    ax.add_patch(Rectangle((-90, -470), 180, 160, facecolor="#EFEFEF", edgecolor=INK, lw=0.8))
    ax.add_patch(Polygon([(-50, -310), (50, -310), (half, 0), (-half, 0)], closed=True,
                         facecolor="#D9D9D9", edgecolor=INK, lw=0.8))
    ax.plot([0, 0], [-520, 420], color=INK, lw=0.6, ls=(0, (8, 3, 1, 3)))
    ax.annotate("", xy=(0, 175), xytext=(0, 80),
                arrowprops=dict(arrowstyle="-|>", lw=1.2, color=INK, mutation_scale=12))
    ax.text(10, 125, "flame", ha="left", va="center", fontsize=8)

    # panels (thickness schematic)
    ax.add_patch(Rectangle((-470, ceil_y), wall_x + tp + 470, tp, facecolor="#BDBDBD",
                           edgecolor=INK, lw=0.7))
    ax.add_patch(Rectangle((wall_x, -420), tp, ceil_y + 420, facecolor="#BDBDBD",
                           edgecolor=INK, lw=0.7))
    # calibration planes
    cal = dict(color=GREY, lw=0.8, ls=(0, (6, 2, 1, 2)))
    ax.plot([-540, wall_x], [ceil_y, ceil_y], **cal)
    ax.plot([wall_x, wall_x], [-520, ceil_y], **cal)

    # thermocouple 100 mm above the ceiling panel upper surface
    tc_y = ceil_y + tp + 100.0
    ax.plot([0, 0], [tc_y, tc_y + 60], color=INK, lw=1.2)
    ax.plot(0, tc_y, marker="o", color=INK, ms=4)

    # dimensions
    ext_line(ax, -half, 0, -135, 0)
    dim_line(ax, -120, 0, -120, ceil_y, "200", offset=(-8, 0), ha="right", va="center")
    ext_line(ax, half, 0, half, 70)
    ext_line(ax, -half, 0, -half, 70)
    dim_line(ax, -half, 55, half, 55, "150", offset=(-40, 3))
    ext_line(ax, half, 0, half, -90)
    dim_line(ax, half, -75, wall_x, -75, "50", offset=(0, -6), va="top")
    ext_line(ax, 10, tc_y, 80, tc_y)
    ext_line(ax, wall_x + tp, ceil_y + tp, wall_x + tp + 10, ceil_y + tp)
    dim_line(ax, 65, ceil_y + tp, 65, tc_y, "100", offset=(8, 0), ha="left", va="center")

    # labels
    ax.text(-530, ceil_y - 8, "ceiling zoning calibration plane (200 mm)", ha="left", va="top",
            fontsize=8)
    ax.text(-460, ceil_y + tp + 8, "ceiling panel", ha="left", va="bottom", fontsize=8)
    ax.text(wall_x + tp + 8, -510, "sidewall zoning calibration plane (50 mm, parallel to axis)",
            rotation=90, ha="left", va="bottom", fontsize=8)
    ax.annotate("sidewall panel", xy=(wall_x + tp, 140), xytext=(200, 140), fontsize=8,
                ha="left", va="center", arrowprops=LEADER)
    ax.annotate("thermocouple 10 cm above\nceiling panel, centred\nover the cone",
                xy=(5, tc_y + 3), xytext=(150, tc_y + 60), fontsize=8, ha="left", va="center",
                arrowprops=LEADER)
    ax.annotate("burner cone", xy=(-62, -150), xytext=(-330, -150), fontsize=8, ha="left",
                va="center", arrowprops=LEADER)
    ax.text(0, -390, "burner", ha="center", va="center", fontsize=8,
            bbox=dict(facecolor="#EFEFEF", edgecolor="none", pad=1.0))
    ax.annotate("burner axis", xy=(0, 380), xytext=(-200, 400), fontsize=8, ha="left",
                va="center", arrowprops=LEADER)
    gravity(ax, 400, 60, 160)

    ax.text(-540, -560, "Cone opening 150 mm × 280 mm (280 mm normal to the drawing).\n"
            "Specimen in one position, insulating material in the other.\n"
            "Dimensions in mm. Panel thickness schematic.",
            ha="left", va="top", fontsize=8, linespacing=1.35)

    ax.set_aspect("equal")
    ax.set_xlim(-550, 460)
    ax.set_ylim(-640, 450)
    ax.axis("off")
    save(fig, "method_rig_partIII")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    fig_zones()
    values = fig_flux()
    print("Part VII 710 mm, net flux at 20/400/600/650/750 C (kW/m2):", fig_flux_part7())
    fig_process()
    fig_global_local()
    fig_projection()
    fig_rig_part7()
    fig_rig_part3()
    print("Net flux at T_wall = 20 C (kW/m2): "
          + ", ".join(f"{z} {v:.1f}" for z, v in values.items()))
