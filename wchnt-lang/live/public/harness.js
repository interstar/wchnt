(function (global) {
  "use strict";

  var WIDTH = 800;
  var HEIGHT = 600;

  function hexColor(n) {
    var x = (n >>> 0) & 0xffffff;
    return "#" + x.toString(16).padStart(6, "0");
  }

  function makeGraphics(ctx) {
    var fill = "#ffffff";
    var stroke = null;
    var lineW = 1;
    var pathOpen = false;
    return {
      clear: function () {
        ctx.fillStyle = "#111111";
        ctx.fillRect(0, 0, WIDTH, HEIGHT);
        pathOpen = false;
      },
      beginFill: function (color) {
        fill = hexColor(color);
        stroke = null;
        pathOpen = false;
      },
      endFill: function () {
        if (pathOpen) {
          ctx.fillStyle = fill;
          ctx.fill();
          pathOpen = false;
        }
      },
      lineStyle: function (width, color) {
        lineW = width;
        stroke = hexColor(color);
      },
      moveTo: function (x, y) {
        ctx.beginPath();
        ctx.moveTo(x, y);
        pathOpen = true;
      },
      lineTo: function (x, y) {
        ctx.lineTo(x, y);
      },
      drawRect: function (x, y, w, h) {
        if (stroke) {
          ctx.strokeStyle = stroke;
          ctx.lineWidth = lineW;
          ctx.strokeRect(x + lineW / 2, y + lineW / 2, w - lineW, h - lineW);
        } else {
          ctx.fillStyle = fill;
          ctx.fillRect(x, y, w, h);
        }
        pathOpen = false;
      },
      drawCircle: function (x, y, r) {
        ctx.fillStyle = fill;
        ctx.beginPath();
        ctx.arc(x, y, r, 0, Math.PI * 2);
        ctx.fill();
        pathOpen = false;
      },
      fillText: function (text, x, y) {
        ctx.fillStyle = fill;
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

  function makeInput(focusEl) {
    var keys = {
      ArrowLeft: false,
      ArrowRight: false,
      ArrowUp: false,
      ArrowDown: false
    };
    var active = false;

    function onDown(e) {
      if (!active) return;
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
      var name = arrowName(e);
      if (!name) return;
      keys[name] = false;
      e.preventDefault();
      e.stopPropagation();
    }

    function clearKeys() {
      keys.ArrowLeft = false;
      keys.ArrowRight = false;
      keys.ArrowUp = false;
      keys.ArrowDown = false;
    }

    return {
      keys: keys,
      attach: function () {
        active = true;
        focusEl.addEventListener("keydown", onDown, true);
        focusEl.addEventListener("keyup", onUp, true);
        focusEl.addEventListener("blur", clearKeys);
        global.addEventListener("keydown", onDown, true);
        global.addEventListener("keyup", onUp, true);
      },
      detach: function () {
        active = false;
        focusEl.removeEventListener("keydown", onDown, true);
        focusEl.removeEventListener("keyup", onUp, true);
        focusEl.removeEventListener("blur", clearKeys);
        global.removeEventListener("keydown", onDown, true);
        global.removeEventListener("keyup", onUp, true);
        clearKeys();
      },
      focus: function () {
        focusEl.focus();
      }
    };
  }

  function create(canvas, keysEl) {
    var ctx = canvas.getContext("2d");
    canvas.width = WIDTH;
    canvas.height = HEIGHT;
    canvas.setAttribute("tabindex", "-1");
    var focusEl = keysEl || canvas;
    var input = makeInput(focusEl);
    var raf = null;
    var running = false;
    var wchntGraphics = makeGraphics(ctx);

    function cancelLoop() {
      running = false;
      if (raf != null) {
        global.cancelAnimationFrame(raf);
        raf = null;
      }
      input.detach();
    }

    return {
      wchntGraphics: wchntGraphics,
      graphics: wchntGraphics,
      input: input,
      start: function (init, step, onError) {
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
      },
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
