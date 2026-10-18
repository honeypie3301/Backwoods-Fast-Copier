/**
 * ARCHITECTURAL NOTICE FOR FUTURE AI ASSISTANTS:
 * If this application ever loads as a blank white screen, check the following 3 requirements:
 * 1. FULL-STACK ENTRYPOINT: package.json scripts must run server.ts, NOT standalone vite:
 *    - "dev": "tsx server.ts" (NOT "vite --port=3000")
 *    - "start": "node server.ts"
 *    This ensures Express API routes (/api/files, /api/files/:filename/content, etc.) are mounted with Vite middlewares.
 * 2. DEPENDENCIES: Ensure "jszip" is installed (npm install jszip / install_applet_package).
 *    If jszip is missing, Vite throws a client-side bundle import error and halts rendering.
 * 3. COMPONENT INTEGRITY: App.tsx must NEVER be overwritten with placeholder templates like "export default function App() { return <div></div>; }".
 *    Always preserve the complete 21KB dashboard component.
 */

import express from 'express';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3000;
const UPLOAD_DIR = path.join(__dirname, 'uploaded_files');

// Ensure uploaded_files directory exists (No placeholder/sample files added - user imports their own)
if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
}

app.use(express.json({ limit: '50mb' }));
app.use(express.text({ limit: '50mb' }));

/**
 * Dynamic version detection logic.
 * Scans both the filename and content for semantic or Minecraft mod version strings (e.g. 1.21.1, 26.1.2).
 */
function detectVersion(filename: string, content?: string): { version: string; source: 'filename' | 'content' | 'metadata' | 'default' } {
  // Strategy 1: Match explicit version numbers in filename like 1.21.1 or 26.1.2 or 1.20.4
  const fnMatch = filename.match(/(\d{1,3}\.\d{1,3}\.\d{1,3})/);
  if (fnMatch && fnMatch[1]) {
    return { version: fnMatch[1], source: 'filename' };
  }

  const fnMatchShort = filename.match(/v?(\d{1,3}\.\d{1,3})/i);
  if (fnMatchShort && fnMatchShort[1] && fnMatchShort[1] !== '1.0') {
    return { version: fnMatchShort[1], source: 'filename' };
  }

  // Strategy 2: Scan content if filename doesn't specify version
  if (content && content.length > 0) {
    const sampleText = content.slice(0, 4096);

    // Look for comments like "// 26.1.2", "// 1.21.1", "for NeoForge 1.21.1", "Version: ... 1.21.1"
    const commentVerMatch = sampleText.match(/(?:neoforge|version|mcversion|target|for)?\s*v?(\d{1,3}\.\d{1,3}\.\d{1,3})/i);
    if (commentVerMatch && commentVerMatch[1]) {
      return { version: commentVerMatch[1], source: 'content' };
    }

    // Look for explicit key-value pairs in JSON/TOML/YAML
    const jsonVersionMatch = sampleText.match(/"(?:version|mcversion|schema_version)"\s*:\s*"([^"]+)"/i);
    if (jsonVersionMatch && jsonVersionMatch[1]) {
      return { version: jsonVersionMatch[1], source: 'content' };
    }

    const tomlYamlMatch = sampleText.match(/(?:version|mcversion)\s*[:=]\s*["']?([^"'\r\n#]+)["']?/i);
    if (tomlYamlMatch && tomlYamlMatch[1]) {
      return { version: tomlYamlMatch[1].trim(), source: 'content' };
    }
  }

  return { version: '1.0.0', source: 'default' };
}

function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

// Active Server-Sent Events client connections
const sseClients = new Set<express.Response>();

function broadcastFilesChanged() {
  const payload = JSON.stringify({ type: 'files_changed', timestamp: Date.now() });
  for (const client of sseClients) {
    try {
      client.write(`data: ${payload}\n\n`);
    } catch {
      sseClients.delete(client);
    }
  }
}

// Debounced filesystem watcher on uploaded_files
let watchDebounceTimer: NodeJS.Timeout | null = null;
try {
  fs.watch(UPLOAD_DIR, (_eventType, filename) => {
    // Ignore dotfiles, temp files, or lock files
    if (filename && (filename.startsWith('.') || filename.endsWith('~') || filename.endsWith('.tmp'))) {
      return;
    }
    if (watchDebounceTimer) {
      clearTimeout(watchDebounceTimer);
    }
    watchDebounceTimer = setTimeout(() => {
      broadcastFilesChanged();
    }, 200);
  });
} catch (err) {
  console.error('Failed to initialize fs.watch on UPLOAD_DIR:', err);
}

// GET /api/files/stream - Server-Sent Events stream for real-time filesystem updates
app.get('/api/files/stream', (req, res) => {
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache, no-transform');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no');
  res.flushHeaders?.();

  // Send initial connected confirmation
  res.write(`data: ${JSON.stringify({ type: 'connected', timestamp: Date.now() })}\n\n`);
  sseClients.add(res);

  // Send keep-alive comments every 20 seconds
  const heartbeat = setInterval(() => {
    try {
      res.write(': keepalive\n\n');
    } catch {
      clearInterval(heartbeat);
      sseClients.delete(res);
    }
  }, 20000);

  req.on('close', () => {
    clearInterval(heartbeat);
    sseClients.delete(res);
  });
});

// GET /api/files - list all files in uploaded_files
app.get('/api/files', (req, res) => {
  try {
    if (!fs.existsSync(UPLOAD_DIR)) {
      fs.mkdirSync(UPLOAD_DIR, { recursive: true });
    }

    const files = fs.readdirSync(UPLOAD_DIR);
    const result = files
      .filter(f => !f.startsWith('.'))
      .map(filename => {
        const filePath = path.join(UPLOAD_DIR, filename);
        const stats = fs.statSync(filePath);
        
        let contentSample = '';
        if (stats.isFile() && stats.size < 100 * 1024) {
          try {
            contentSample = fs.readFileSync(filePath, 'utf-8');
          } catch (e) {
            // unreadable
          }
        }

        const { version, source } = detectVersion(filename, contentSample);
        const ext = path.extname(filename).toLowerCase().replace('.', '') || 'file';

        return {
          name: filename,
          size: stats.size,
          sizeFormatted: formatBytes(stats.size),
          updatedAt: stats.mtime.toISOString(),
          version,
          versionSource: source,
          extension: ext,
          isText: true
        };
      })
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));

    res.json({ success: true, count: result.length, files: result });
  } catch (error: any) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// GET /api/files/:filename/content - get single file content
app.get('/api/files/:filename/content', (req, res) => {
  try {
    const filename = path.basename(req.params.filename);
    const filePath = path.join(UPLOAD_DIR, filename);

    if (!fs.existsSync(filePath)) {
      return res.status(404).json({ success: false, error: 'File not found' });
    }

    const content = fs.readFileSync(filePath, 'utf-8');
    const stats = fs.statSync(filePath);
    const { version, source } = detectVersion(filename, content);

    res.json({
      success: true,
      name: filename,
      content,
      size: stats.size,
      sizeFormatted: formatBytes(stats.size),
      version,
      versionSource: source
    });
  } catch (error: any) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// POST /api/files/batch-contents - optimized prefetching for multiple files
app.post('/api/files/batch-contents', (req, res) => {
  try {
    const { filenames } = req.body;
    if (!Array.isArray(filenames)) {
      return res.status(400).json({ success: false, error: 'filenames array required' });
    }

    const contentsMap: Record<string, { content: string; size: number; version: string }> = {};

    for (const name of filenames) {
      const safeName = path.basename(name);
      const filePath = path.join(UPLOAD_DIR, safeName);
      if (fs.existsSync(filePath)) {
        try {
          const content = fs.readFileSync(filePath, 'utf-8');
          const stats = fs.statSync(filePath);
          const { version } = detectVersion(safeName, content);
          contentsMap[safeName] = {
            content,
            size: stats.size,
            version
          };
        } catch (e) {
          // skip
        }
      }
    }

    res.json({ success: true, contents: contentsMap });
  } catch (error: any) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// POST /api/files - upload or save file in uploaded_files
app.post('/api/files', (req, res) => {
  try {
    const { name, content } = req.body;
    if (!name || typeof name !== 'string') {
      return res.status(400).json({ success: false, error: 'File name is required' });
    }

    const safeName = path.basename(name).replace(/[^a-zA-Z0-9_.-]/g, '_');
    const filePath = path.join(UPLOAD_DIR, safeName);

    const fileContent = typeof content === 'string' ? content : JSON.stringify(content, null, 2);
    fs.writeFileSync(filePath, fileContent, 'utf-8');

    const stats = fs.statSync(filePath);
    const { version, source } = detectVersion(safeName, fileContent);

    broadcastFilesChanged();

    res.json({
      success: true,
      file: {
        name: safeName,
        size: stats.size,
        sizeFormatted: formatBytes(stats.size),
        updatedAt: stats.mtime.toISOString(),
        version,
        versionSource: source,
        extension: path.extname(safeName).replace('.', '') || 'file'
      }
    });
  } catch (error: any) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// DELETE /api/files/:filename - delete file
app.delete('/api/files/:filename', (req, res) => {
  try {
    const filename = path.basename(req.params.filename);
    const filePath = path.join(UPLOAD_DIR, filename);

    if (!fs.existsSync(filePath)) {
      return res.status(404).json({ success: false, error: 'File not found' });
    }

    fs.unlinkSync(filePath);
    broadcastFilesChanged();
    res.json({ success: true, message: `File ${filename} deleted successfully.` });
  } catch (error: any) {
    res.status(500).json({ success: false, error: error.message });
  }
});

// Catch-all 404 for API routes to guarantee JSON output instead of Vite HTML
app.all('/api/*', (req, res) => {
  res.status(404).json({ success: false, error: `API route not found: ${req.method} ${req.path}` });
});

async function startServer() {
  if (process.env.NODE_ENV !== 'production') {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa'
    });
    app.use(vite.middlewares);
  } else {
    app.use(express.static(path.join(__dirname, 'dist')));
    app.get('*', (req, res) => {
      res.sendFile(path.join(__dirname, 'dist', 'index.html'));
    });
  }

  app.listen(PORT, () => {
    console.log(`Server running on http://localhost:${PORT}`);
  });
}

startServer();
