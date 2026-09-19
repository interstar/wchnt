"use strict";

const { app, BrowserWindow, protocol, net, shell, Menu } = require("electron");
const path = require("path");
const { pathToFileURL } = require("url");

const PUBLIC_DIR = path.resolve(__dirname, "..", "public");

// Unpackaged Linux often lacks a setuid chrome-sandbox; without this, Electron aborts.
if (process.platform === "linux" && !app.isPackaged) {
  app.commandLine.appendSwitch("no-sandbox");
}

protocol.registerSchemesAsPrivileged([
  {
    scheme: "wchnt",
    privileges: {
      standard: true,
      secure: true,
      supportFetchAPI: true,
      corsEnabled: true,
      stream: true
    }
  }
]);

function publicFile(requestUrl) {
  const { pathname } = new URL(requestUrl);
  let rel = decodeURIComponent(pathname);
  if (rel === "" || rel === "/") {
    rel = "/index.html";
  }
  if (rel.endsWith("/")) {
    rel += "index.html";
  }
  const resolved = path.normalize(path.join(PUBLIC_DIR, rel));
  const root = path.normalize(PUBLIC_DIR + path.sep);
  if (resolved !== path.normalize(PUBLIC_DIR) && !resolved.startsWith(root)) {
    return null;
  }
  return resolved;
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1100,
    height: 800,
    minWidth: 640,
    minHeight: 480,
    backgroundColor: "#111111",
    title: "WCHNT live",
    icon: path.join(PUBLIC_DIR, "icons", "icon-512.png"),
    webPreferences: {
      contextIsolation: true,
      sandbox: true
    }
  });

  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: "deny" };
  });

  win.loadURL("wchnt://live/index.html");
}

function buildMenu() {
  const template = [
    {
      label: "File",
      submenu: [
        {
          label: "Reset wiki…",
          accelerator: "CmdOrCtrl+Shift+Alt+W",
          click: (_item, focusedWindow) => {
            if (focusedWindow) {
              focusedWindow.webContents.executeJavaScript("wchntReset()");
            }
          }
        },
        { type: "separator" },
        { role: "quit" }
      ]
    },
    {
      label: "Edit",
      submenu: [
        { role: "undo" },
        { role: "redo" },
        { type: "separator" },
        { role: "cut" },
        { role: "copy" },
        { role: "paste" },
        { role: "selectAll" }
      ]
    },
    {
      label: "View",
      submenu: [
        { role: "reload" },
        { role: "forceReload" },
        { role: "toggleDevTools" },
        { type: "separator" },
        { role: "resetZoom" },
        { role: "zoomIn" },
        { role: "zoomOut" },
        { type: "separator" },
        { role: "togglefullscreen" }
      ]
    },
    {
      label: "Window",
      submenu: [
        { role: "minimize" },
        { role: "close" }
      ]
    }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

app.whenReady().then(() => {
  protocol.handle("wchnt", (request) => {
    const filePath = publicFile(request.url);
    if (!filePath) {
      return new Response("Forbidden", { status: 403 });
    }
    return net.fetch(pathToFileURL(filePath).href);
  });

  buildMenu();
  createWindow();

  app.on("activate", () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow();
    }
  });
});

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") {
    app.quit();
  }
});
