(function (global) {
  "use strict";

  var WIDTH = 800;
  var HEIGHT = 600;

  function hexColor(n) {
    var x = (n >>> 0) & 0xffffff;
    return "#" + x.toString(16).padStart(6, "0");
  }

  function makeGraphics(ctx) {
    var bgColor = 0x111111;
    var bgAlpha = 1;
    var fill = null;      // {color, alpha}
    var stroke = null;    // {width, color, alpha}
    var pathOpen = false;

    function toCss(color, alpha) {
      var hex = hexColor(color);
      if (alpha >= 1) return hex;
      var r = (color >>> 16) & 0xff;
      var g = (color >>> 8) & 0xff;
      var b = color & 0xff;
      return "rgba(" + r + "," + g + "," + b + "," + alpha + ")";
    }

    function beginPath() {
      ctx.beginPath();
      pathOpen = true;
    }

    function fillPath() {
      if (fill) {
        ctx.fillStyle = toCss(fill.color, fill.alpha);
        ctx.fill();
      }
    }

    function strokePath() {
      if (stroke) {
        ctx.strokeStyle = toCss(stroke.color, stroke.alpha);
        ctx.lineWidth = stroke.width;
        ctx.stroke();
      }
    }

    function paintBackground() {
      ctx.fillStyle = toCss(bgColor, bgAlpha);
      ctx.fillRect(0, 0, WIDTH, HEIGHT);
    }

    return {
      background: function (color, alpha) {
        bgColor = color;
        bgAlpha = (alpha === undefined) ? 1 : alpha;
      },

      clear: function () {
        fill = null;
        stroke = null;
        paintBackground();
        pathOpen = false;
      },

      beginFill: function (color, alpha) {
        fill = { color: color, alpha: (alpha === undefined) ? 1 : alpha };
        beginPath();
      },

      endFill: function () {
        if (pathOpen) {
          ctx.closePath();
          fillPath();
          strokePath();
        }
        fill = null;
        pathOpen = false;
      },

      lineStyle: function (width, color, alpha) {
        if (width === undefined || width === null) {
          stroke = null;
          return;
        }
        stroke = { width: width, color: color, alpha: (alpha === undefined) ? 1 : alpha };
      },

      noStroke: function () {
        stroke = null;
      },

      moveTo: function (x, y) {
        if (!pathOpen) beginPath();
        ctx.moveTo(x, y);
      },

      lineTo: function (x, y) {
        if (!pathOpen) beginPath();
        ctx.lineTo(x, y);
      },

      drawLine: function (x1, y1, x2, y2) {
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        strokePath();
        pathOpen = false;
      },

      drawRect: function (x, y, w, h) {
        ctx.beginPath();
        ctx.rect(x, y, w, h);
        fillPath();
        strokePath();
        pathOpen = false;
      },

      drawCircle: function (x, y, r) {
        ctx.beginPath();
        ctx.arc(x, y, r, 0, Math.PI * 2);
        fillPath();
        strokePath();
        pathOpen = false;
      },

      drawEllipse: function (x, y, rx, ry) {
        ctx.beginPath();
        if (ctx.ellipse) {
          ctx.ellipse(x, y, rx, ry, 0, 0, Math.PI * 2);
        } else {
          ctx.save();
          ctx.translate(x, y);
          ctx.scale(rx, ry);
          ctx.arc(0, 0, 1, 0, Math.PI * 2);
          ctx.restore();
        }
        fillPath();
        strokePath();
        pathOpen = false;
      },

      fillText: function (text, x, y) {
        ctx.fillStyle = fill ? toCss(fill.color, fill.alpha) : "#ffffff";
        ctx.font = "16px sans-serif";
        ctx.textBaseline = "top";
        ctx.fillText(String(text), x, y);
      }
    };
  }

  function arrowName(evt) {
    var byCode = {
      ArrowUp: "ArrowUp",
      ArrowDown: "ArrowDown",
      ArrowLeft: "ArrowLeft",
      ArrowRight: "ArrowRight"
    };
    return byCode[evt.code] || byCode[evt.key] || null;
  }

  function makeInput(focusEl, mouseEl) {
    var keys = {
      ArrowLeft: false,
      ArrowRight: false,
      ArrowUp: false,
      ArrowDown: false,
      Shift: false
    };
    var mouse = { x: 0.5, y: 0.5 };
    var active = false;
    var pointerEl = mouseEl || focusEl;

    function clamp01(v) {
      if (v < 0) return 0;
      if (v > 1) return 1;
      return v;
    }

    function onDown(e) {
      if (!active) return;
      if (e.key === "Shift" || e.code === "ShiftLeft" || e.code === "ShiftRight") {
        keys.Shift = true;
        e.preventDefault();
        return;
      }
      var name = arrowName(e);
      if (!name) return;
      keys[name] = true;
      e.preventDefault();
      e.stopPropagation();
      if (global.document.activeElement !== focusEl) {
        focusEl.focus();
      }
    }

    function onUp(e) {
      if (!active) return;
      if (e.key === "Shift" || e.code === "ShiftLeft" || e.code === "ShiftRight") {
        keys.Shift = false;
        e.preventDefault();
        return;
      }
      var name = arrowName(e);
      if (!name) return;
      keys[name] = false;
      e.preventDefault();
      e.stopPropagation();
    }

    function onMove(e) {
      if (!active) return;
      var rect = pointerEl.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return;
      mouse.x = clamp01((e.clientX - rect.left) / rect.width);
      mouse.y = clamp01((e.clientY - rect.top) / rect.height);
    }

    function clearKeys() {
      keys.ArrowLeft = false;
      keys.ArrowRight = false;
      keys.ArrowUp = false;
      keys.ArrowDown = false;
      keys.Shift = false;
    }

    return {
      keys: keys,
      mouse: mouse,
      attach: function () {
        active = true;
        focusEl.addEventListener("keydown", onDown, true);
        focusEl.addEventListener("keyup", onUp, true);
        focusEl.addEventListener("blur", clearKeys);
        global.addEventListener("keydown", onDown, true);
        global.addEventListener("keyup", onUp, true);
        pointerEl.addEventListener("mousemove", onMove, true);
      },
      detach: function () {
        active = false;
        focusEl.removeEventListener("keydown", onDown, true);
        focusEl.removeEventListener("keyup", onUp, true);
        focusEl.removeEventListener("blur", clearKeys);
        global.removeEventListener("keydown", onDown, true);
        global.removeEventListener("keyup", onUp, true);
        pointerEl.removeEventListener("mousemove", onMove, true);
        clearKeys();
      },
      focus: function () {
        focusEl.focus();
      }
    };
  }

  function format(value) {
    if (value === null || value === undefined) return String(value);
    var t = typeof value;
    if (t === "string") return value;
    if (t === "number" || t === "boolean") return String(value);
    if (t === "function") return "[function]";
    if (value && typeof value.toConstruction === "function") {
      try {
        return value.toConstruction();
      } catch (e) {}
    }
    if (Array.isArray(value)) {
      return "[:Array " + value.map(format).join(" ") + "]";
    }
    if (typeof Map !== "undefined" && value instanceof Map) {
      var parts = [];
      value.forEach(function (v, k) {
        parts.push(format(k) + ": " + format(v));
      });
      return "{" + parts.join(" ") + "}";
    }
    try {
      return String(value);
    } catch (e) {
      return Object.prototype.toString.call(value);
    }
  }

  function makeConsole(transcriptEl) {
    function write(text) {
      transcriptEl.textContent += text;
      transcriptEl.scrollTop = transcriptEl.scrollHeight;
    }
    return {
      print: function (s) {
        write(format(s));
      },
      println: function (s) {
        write(format(s) + "\n");
      },
      clear: function () {
        transcriptEl.textContent = "";
      }
    };
  }

  function makeMaths() {
    function failNonPos(n) {
      if (!(n > 0)) throw new Error("WCHNTMaths.randInt requires n > 0");
    }
    return {
      rand: function () { return Math.random(); },
      pi: function () { return Math.PI; },
      randInt: function (n) {
        failNonPos(n);
        return Math.floor(Math.random() * n);
      },
      hsv: function (h, s, v) {
        var hh = ((h % 1) + 1) % 1;
        var i = Math.floor(hh * 6);
        var f = hh * 6 - i;
        var p = v * (1 - s);
        var q = v * (1 - f * s);
        var t = v * (1 - (1 - f) * s);
        var r, g, b;
        switch (((i % 6) + 6) % 6) {
          case 0: r = v; g = t; b = p; break;
          case 1: r = q; g = v; b = p; break;
          case 2: r = p; g = v; b = t; break;
          case 3: r = p; g = q; b = v; break;
          case 4: r = t; g = p; b = v; break;
          default: r = v; g = p; b = q; break;
        }
        function ch(x) {
          var n = Math.floor(x * 255);
          if (n < 0) return 0;
          if (n > 255) return 255;
          return n;
        }
        return (ch(r) << 16) | (ch(g) << 8) | ch(b);
      },
      sin: function (x) { return Math.sin(x); },
      cos: function (x) { return Math.cos(x); },
      tan: function (x) { return Math.tan(x); },
      asin: function (x) { return Math.asin(x); },
      acos: function (x) { return Math.acos(x); },
      atan: function (x) { return Math.atan(x); },
      atan2: function (y, x) { return Math.atan2(y, x); },
      abs: function (x) { return Math.abs(x); },
      floor: function (x) { return Math.floor(x); },
      ceil: function (x) { return Math.ceil(x); },
      round: function (x) { return Math.round(x); },
      sqrt: function (x) { return Math.sqrt(x); },
      log: function (x) { return Math.log(x); },
      exp: function (x) { return Math.exp(x); },
      pow: function (x, y) { return Math.pow(x, y); },
      min: function (x, y) { return Math.min(x, y); },
      max: function (x, y) { return Math.max(x, y); }
    };
  }

  function create(canvas, keysEl, transcriptEl, lineEl) {
    var ctx = canvas.getContext("2d");
    canvas.width = WIDTH;
    canvas.height = HEIGHT;
    canvas.setAttribute("tabindex", "-1");
    var focusEl = keysEl || canvas;
    var input = makeInput(focusEl, canvas);
    var raf = null;
    var running = false;
    var cliOnKey = null;
    var wchntGraphics = makeGraphics(ctx);
    var wchntConsole = makeConsole(transcriptEl);
    var wchntMaths = makeMaths();

    function stopCli() {
      if (cliOnKey && lineEl) {
        lineEl.removeEventListener("keydown", cliOnKey);
        cliOnKey = null;
      }
    }

    function cancelLoop() {
      running = false;
      if (raf != null) {
        global.cancelAnimationFrame(raf);
        raf = null;
      }
      input.detach();
      stopCli();
    }

    function start(init, step, onError) {
      cancelLoop();
      running = true;
      input.attach();
      input.focus();
      try {
        init();
      } catch (e) {
        running = false;
        input.detach();
        if (onError) onError(e);
        return;
      }
      function frame() {
        if (!running) return;
        try {
          step();
        } catch (e) {
          cancelLoop();
          if (onError) onError(e);
          return;
        }
        raf = global.requestAnimationFrame(frame);
      }
      raf = global.requestAnimationFrame(frame);
    }

    function startCli(init, step, onError) {
      cancelLoop();
      wchntConsole.clear();
      if (lineEl) lineEl.value = "";
      running = true;
      try {
        init();
      } catch (e) {
        running = false;
        if (onError) onError(e);
        return;
      }
      cliOnKey = function (e) {
        if (e.key !== "Enter") return;
        e.preventDefault();
        if (!running) return;
        var line = lineEl.value;
        lineEl.value = "";
        wchntConsole.println(line);
        try {
          step(line);
        } catch (err) {
          cancelLoop();
          if (onError) onError(err);
        }
      };
      lineEl.addEventListener("keydown", cliOnKey);
      lineEl.focus();
    }

    return {
      wchntGraphics: wchntGraphics,
      graphics: wchntGraphics,
      wchntConsole: wchntConsole,
      wchntMaths: wchntMaths,
      input: input,
      start: start,
      startCli: startCli,
      stop: function () {
        cancelLoop();
      },
      isRunning: function () {
        return running;
      }
    };
  }

  global.WCHNTHarness = { create: create };
})(window);
