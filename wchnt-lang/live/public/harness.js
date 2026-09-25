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
      if (alpha === undefined && (color > 0xffffff || color < 0)) {
        alpha = ((color >>> 24) & 0xff) / 255;
      }
      if (alpha === undefined) alpha = 1;
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

    var self = {
      color: function (r, g, b, a) {
        if (g === undefined) g = b = r;
        if (a === undefined) a = 255;
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        a = Math.max(0, Math.min(255, a));
        return ((a << 24) | (r << 16) | (g << 8) | b);
      },

      red: function (color) { return (color >>> 16) & 0xff; },
      green: function (color) { return (color >>> 8) & 0xff; },
      blue: function (color) { return color & 0xff; },
      alpha: function (color) { return (color >>> 24) & 0xff; },

      background: function (color, alpha) {
        bgColor = color;
        bgAlpha = alpha;
        return self;
      },

      clear: function () {
        fill = null;
        stroke = null;
        paintBackground();
        pathOpen = false;
        return self;
      },

      beginFill: function (color, alpha) {
        fill = { color: color, alpha: alpha };
        beginPath();
        return self;
      },

      endFill: function () {
        if (pathOpen) {
          ctx.closePath();
          fillPath();
          strokePath();
        }
        fill = null;
        pathOpen = false;
        return self;
      },

      lineStyle: function (width, color, alpha) {
        if (width === undefined || width === null) {
          stroke = null;
          return self;
        }
        stroke = { width: width, color: color, alpha: alpha };
        return self;
      },

      noStroke: function () {
        stroke = null;
        return self;
      },

      moveTo: function (x, y) {
        if (!pathOpen) beginPath();
        ctx.moveTo(x, y);
        return self;
      },

      lineTo: function (x, y) {
        if (!pathOpen) beginPath();
        ctx.lineTo(x, y);
        return self;
      },

      drawLine: function (x1, y1, x2, y2) {
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        strokePath();
        pathOpen = false;
        return self;
      },

      drawRect: function (x, y, w, h) {
        ctx.beginPath();
        ctx.rect(x, y, w, h);
        fillPath();
        strokePath();
        pathOpen = false;
        return self;
      },

      drawCircle: function (x, y, r) {
        ctx.beginPath();
        ctx.arc(x, y, r, 0, Math.PI * 2);
        fillPath();
        strokePath();
        pathOpen = false;
        return self;
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
        return self;
      },

      fillText: function (text, x, y) {
        ctx.fillStyle = fill ? toCss(fill.color, fill.alpha) : "#ffffff";
        ctx.font = "16px sans-serif";
        ctx.textBaseline = "top";
        ctx.fillText(String(text), x, y);
        return self;
      }
    };
    return self;
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
    for (var d = 0; d <= 9; d++) { keys[String(d)] = false; }
    var mouse = { x: 0.5, y: 0.5, down: false };
    var pressedKeys = [];
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
        pressedKeys.push("Shift");
        e.preventDefault();
        return;
      }
      if (/^[0-9]$/.test(e.key)) {
        keys[e.key] = true;
        pressedKeys.push(e.key);
        e.preventDefault();
        e.stopPropagation();
        return;
      }
      var name = arrowName(e);
      if (!name) return;
      keys[name] = true;
      pressedKeys.push(name);
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
      if (/^[0-9]$/.test(e.key)) {
        keys[e.key] = false;
        e.preventDefault();
        e.stopPropagation();
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

    function onMouseDown() {
      if (!active) return;
      mouse.down = true;
    }

    function onMouseUp() {
      if (!active) return;
      mouse.down = false;
    }

    function clearKeys() {
      keys.ArrowLeft = false;
      keys.ArrowRight = false;
      keys.ArrowUp = false;
      keys.ArrowDown = false;
      keys.Shift = false;
      for (var d = 0; d <= 9; d++) { keys[String(d)] = false; }
      pressedKeys = [];
      mouse.down = false;
    }

    function surfaceWidth() {
      return (mouseEl && mouseEl.width) || (pointerEl && pointerEl.clientWidth) || 1;
    }

    function surfaceHeight() {
      return (mouseEl && mouseEl.height) || (pointerEl && pointerEl.clientHeight) || 1;
    }

    return {
      keys: keys,
      mouse: mouse,
      mouseX: function () { return Math.round(mouse.x * surfaceWidth()); },
      mouseY: function () { return Math.round(mouse.y * surfaceHeight()); },
      mouseNX: function () { return mouse.x; },
      mouseNY: function () { return mouse.y; },
      mouseDown: function () { return mouse.down; },
      keyDown: function (key) { return !!keys[String(key)]; },
      keyPresses: function () {
        var result = pressedKeys.slice();
        pressedKeys = [];
        return result;
      },
      attach: function () {
        active = true;
        focusEl.addEventListener("keydown", onDown, true);
        focusEl.addEventListener("keyup", onUp, true);
        focusEl.addEventListener("blur", clearKeys);
        global.addEventListener("keydown", onDown, true);
        global.addEventListener("keyup", onUp, true);
        pointerEl.addEventListener("mousemove", onMove, true);
        if (focusEl !== pointerEl) {
          focusEl.addEventListener("mousemove", onMove, true);
        }
        pointerEl.addEventListener("mousedown", onMouseDown, true);
        if (focusEl !== pointerEl) {
          focusEl.addEventListener("mousedown", onMouseDown, true);
        }
        global.addEventListener("mouseup", onMouseUp, true);
      },
      detach: function () {
        active = false;
        focusEl.removeEventListener("keydown", onDown, true);
        focusEl.removeEventListener("keyup", onUp, true);
        focusEl.removeEventListener("blur", clearKeys);
        global.removeEventListener("keydown", onDown, true);
        global.removeEventListener("keyup", onUp, true);
        pointerEl.removeEventListener("mousemove", onMove, true);
        if (focusEl !== pointerEl) {
          focusEl.removeEventListener("mousemove", onMove, true);
        }
        pointerEl.removeEventListener("mousedown", onMouseDown, true);
        if (focusEl !== pointerEl) {
          focusEl.removeEventListener("mousedown", onMouseDown, true);
        }
        global.removeEventListener("mouseup", onMouseUp, true);
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
    var self = {
      format: format,
      print: function (s) {
        write(format(s));
        return self;
      },
      println: function (s) {
        write(format(s) + "\n");
        return self;
      },
      clear: function () {
        transcriptEl.textContent = "";
        return self;
      }
    };
    return self;
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

  function makeForm(root) {
    var events = [];
    var graphics = {};

    function value(node, name) {
      return node[name];
    }

    function event(id, kind, value) {
      events.push({id: id, kind: kind, value: value});
    }

    function addCommonInput(input, id, kind) {
      input.addEventListener("input", function () {
        event(id, kind, input.value);
      });
      input.addEventListener("change", function () {
        event(id, kind, input.value);
      });
    }

    function render(node) {
      var kind = node.__wchntClass;
      if (kind === "Form") return render(value(node, "root"));
      if (kind === "Panel") {
        var panel = document.createElement("section");
        panel.className = "wchnt-form-panel";
        panel.dataset.wchntId = value(node, "id");
        var children = value(node, "children") || [];
        for (var i = 0; i < children.length; i++) {
          panel.appendChild(render(children[i]));
        }
        return panel;
      }
      if (kind === "Label") {
        var label = document.createElement("label");
        label.dataset.wchntId = value(node, "id");
        label.textContent = value(node, "text");
        return label;
      }
      if (kind === "TextInput" || kind === "TextArea") {
        var input = kind === "TextArea" ? document.createElement("textarea") : document.createElement("input");
        var id = value(node, "id");
        input.value = value(node, "value");
        input.dataset.wchntId = id;
        if (kind === "TextArea") {
          input.rows = value(node, "rows");
          input.cols = value(node, "cols");
        } else {
          input.type = "text";
        }
        addCommonInput(input, id, kind);
        return input;
      }
      if (kind === "Slider") {
        var slider = document.createElement("input");
        var sliderId = value(node, "id");
        slider.type = "range";
        slider.min = value(node, "min");
        slider.max = value(node, "max");
        slider.step = value(node, "step");
        slider.value = value(node, "value");
        slider.dataset.wchntId = sliderId;
        addCommonInput(slider, sliderId, kind);
        return slider;
      }
      if (kind === "Button") {
        var button = document.createElement("button");
        var buttonId = value(node, "id");
        button.type = "button";
        button.textContent = value(node, "text");
        button.dataset.wchntId = buttonId;
        button.addEventListener("click", function () { event(buttonId, kind, true); });
        return button;
      }
      if (kind === "Options") {
        var select = document.createElement("select");
        var selectId = value(node, "id");
        var options = value(node, "options") || [];
        for (var o = 0; o < options.length; o++) {
          var option = document.createElement("option");
          option.value = value(options[o], "value");
          option.textContent = value(options[o], "label");
          select.appendChild(option);
        }
        select.value = value(node, "selected");
        select.dataset.wchntId = selectId;
        addCommonInput(select, selectId, kind);
        return select;
      }
      if (kind === "Canvas") {
        var canvas = document.createElement("canvas");
        var canvasId = value(node, "id");
        canvas.width = value(node, "width");
        canvas.height = value(node, "height");
        canvas.dataset.wchntId = canvasId;
        graphics[canvasId] = makeGraphics(canvas.getContext("2d"));
        return canvas;
      }
      throw new Error("WCHNTForm cannot render " + kind);
    }

    var self = {
      mount: function (form) {
        while (root.firstChild) root.removeChild(root.firstChild);
        graphics = {};
        root.appendChild(render(form));
        return self;
      },
      value: function (id) {
        var node = root.querySelector('[data-wchnt-id="' + id + '"]');
        if (!node) throw new Error("No form control named '" + id + "'");
        return node.value;
      },
      number: function (id) {
        return parseFloat(this.value(id));
      },
      pollEvents: function () {
        var result = events;
        events = [];
        return result;
      },
      graphics: function (id) {
        if (!graphics[id]) throw new Error("No form canvas named '" + id + "'");
        return graphics[id];
      },
      clear: function () {
        while (root.firstChild) root.removeChild(root.firstChild);
        graphics = {};
        events = [];
        return self;
      }
    };
    return self;
  }

  function create(canvas, keysEl, transcriptEl, lineEl, formRoot) {
    var ctx = canvas.getContext("2d");
    canvas.width = WIDTH;
    canvas.height = HEIGHT;
    canvas.setAttribute("tabindex", "-1");
    var focusEl = keysEl || canvas;
    var wchntInput = makeInput(focusEl, canvas);
    var raf = null;
    var running = false;
    var cliOnKey = null;
    var wchntGraphics = makeGraphics(ctx);
    var wchntConsole = makeConsole(transcriptEl);
    var wchntMaths = makeMaths();
    var wchntForm = makeForm(formRoot);

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
      wchntInput.detach();
      stopCli();
    }

    function start(init, step, onError) {
      cancelLoop();
      running = true;
      wchntInput.attach();
      wchntInput.focus();
      try {
        init();
      } catch (e) {
        running = false;
        wchntInput.detach();
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
      wchntConsole: wchntConsole,
      wchntMaths: wchntMaths,
      wchntForm: wchntForm,
      wchntInput: wchntInput,
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
