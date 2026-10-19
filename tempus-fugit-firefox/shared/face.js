// The analog face (plan 6.1) on a <canvas>: the same geometry as the Android
// FaceRenderer. The canvas keeps its size; only its pixels are redrawn.

import * as M from "./model.js";

function palette(canvas) {
  const s = getComputedStyle(canvas);
  const v = (name) => s.getPropertyValue(name).trim();
  return {
    ink: v("--ink"), dim: v("--dim"), ghost: v("--ghost"), halo: v("--halo"),
    fast: v("--fast"), slow: v("--slow"),
  };
}

const x = (cx, len, a) => cx + len * Math.sin((a * Math.PI) / 180);
const y = (cy, len, a) => cy - len * Math.cos((a * Math.PI) / 180);

export class Face {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext("2d");
    this.size = 0;
  }

  /** Size the backing store once for the CSS size and pixel ratio. */
  fit() {
    const css = this.canvas.clientWidth;
    const px = Math.round(css * (window.devicePixelRatio || 1));
    if (px !== this.size) {
      this.size = px;
      this.canvas.width = px;
      this.canvas.height = px;
    }
  }

  draw(f, secondHand) {
    this.fit();
    const c = this.ctx, p = palette(this.canvas);
    const r = (this.size / 2) * 0.98, cx = this.size / 2, cy = this.size / 2;
    c.clearRect(0, 0, this.size, this.size);
    c.lineCap = "round";

    c.strokeStyle = p.dim;
    c.lineWidth = r * 0.015;
    c.beginPath();
    c.arc(cx, cy, r * 0.97, 0, 2 * Math.PI);
    c.stroke();
    for (let i = 0; i < 12; i++) {
      const a = i * 30;
      c.strokeStyle = i === 0 ? p.ink : p.dim;
      c.lineWidth = r * (i % 3 === 0 ? 0.035 : 0.022);
      const inner = r * (i === 0 ? M.TICK_INNER - 0.04 : M.TICK_INNER);
      c.beginPath();
      c.moveTo(x(cx, inner, a), y(cy, inner, a));
      c.lineTo(x(cx, r * M.TICK_OUTER, a), y(cy, r * M.TICK_OUTER, a));
      c.stroke();
    }

    c.textAlign = "center";
    c.font = `${r * 0.075}px system-ui, sans-serif`;
    if (!f.hasFix) {
      this.text(c, p, "no fix yet", cx, cy + r * M.LINE_OFFSETS[0], r);
      this.hub(c, p, cx, cy, r);
      return;
    }

    const sweep = M.arcSweep(f.arc);
    if (Math.abs(sweep) >= 0.5) {
      c.strokeStyle = sweep > 0 ? p.fast : p.slow;
      c.lineWidth = r * 0.03;
      const start = -Math.PI / 2, end = start + (sweep * Math.PI) / 180;
      c.beginPath();
      c.arc(cx, cy, r * M.ARC_RADIUS - r * 0.015, start, end, sweep < 0);
      c.stroke();
    }

    this.hands(c, cx, cy, r, M.handAngles(f.targetMs, f.seconds), p.ghost, secondHand);
    this.hands(c, cx, cy, r, M.handAngles(f.shownMs, f.seconds), p.ink, secondHand);

    this.text(c, p, f.speedLine, cx, cy + r * M.LINE_OFFSETS[0], r);
    this.text(c, p, f.gapLine, cx, cy + r * M.LINE_OFFSETS[1], r);
    if (f.settleLine) this.text(c, p, f.settleLine, cx, cy + r * M.LINE_OFFSETS[2], r);
    this.hub(c, p, cx, cy, r);
  }

  text(c, p, s, tx, ty, r) {
    c.lineWidth = r * 0.03;
    c.strokeStyle = p.halo;
    c.strokeText(s, tx, ty);
    c.fillStyle = p.ink;
    c.fillText(s, tx, ty);
  }

  hands(c, cx, cy, r, a, color, secondHand) {
    c.strokeStyle = color;
    const hand = (len, angle, width) => {
      c.lineWidth = r * width;
      c.beginPath();
      c.moveTo(cx, cy);
      c.lineTo(x(cx, r * len, angle), y(cy, r * len, angle));
      c.stroke();
    };
    hand(M.HOUR_LENGTH, a[0], 0.05);
    hand(M.MINUTE_LENGTH, a[1], 0.032);
    if (secondHand) hand(M.SECOND_LENGTH, a[2], 0.012);
  }

  hub(c, p, cx, cy, r) {
    c.fillStyle = p.ink;
    c.beginPath();
    c.arc(cx, cy, r * 0.035, 0, 2 * Math.PI);
    c.fill();
  }
}
