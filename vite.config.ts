import path from 'path';
import fs from 'fs';
import crypto from 'crypto';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, '.', '');
    return {
      server: {
        port: 3000,
        host: '0.0.0.0',
      },
      plugins: [
        {
          name: 'cloudstream-repo-server',
          configureServer(server) {
            server.middlewares.use((req, res, next) => {
              const url = req.url ? req.url.split('?')[0] : '';
              
              // Determine current base URL from request headers (highly robust for proxy environments)
              const host = req.headers['x-forwarded-host'] || req.headers.host || 'localhost:3000';
              const proto = req.headers['x-forwarded-proto'] || 'https';
              const baseUrl = `${proto}://${host}`;

              // Serve plugins.json (dynamically rewrite URLs to point to local server with correct sizes and hashes)
              if (url === '/plugins.json' || url === '/build/plugins.json') {
                const pluginsJsonPath = path.resolve(__dirname, 'build/plugins.json');
                if (fs.existsSync(pluginsJsonPath)) {
                  try {
                    const content = fs.readFileSync(pluginsJsonPath, 'utf8');
                    const plugins = JSON.parse(content);
                    const modifiedPlugins = plugins.map((p: any) => {
                      const pluginName = p.internalName || p.name;
                      const cs3Path = path.resolve(__dirname, `${pluginName}/build/${pluginName}.cs3`);
                      let fileSize = p.fileSize;
                      let fileHash = p.fileHash;

                      if (fs.existsSync(cs3Path)) {
                        try {
                          const stats = fs.statSync(cs3Path);
                          fileSize = stats.size;
                          const fileBuffer = fs.readFileSync(cs3Path);
                          const hashSum = crypto.createHash('sha256');
                          hashSum.update(fileBuffer);
                          fileHash = `sha256-${hashSum.digest('hex')}`;
                        } catch (e) {
                          console.error('Error computing hash/size for', pluginName, e);
                        }
                      }

                      return {
                        ...p,
                        url: `${baseUrl}/${pluginName}.cs3`,
                        fileSize,
                        fileHash
                      };
                    });
                    res.setHeader('Content-Type', 'application/json; charset=utf-8');
                    res.setHeader('Access-Control-Allow-Origin', '*');
                    res.end(JSON.stringify(modifiedPlugins, null, 2));
                    return;
                  } catch (e) {
                    res.statusCode = 500;
                    res.end(JSON.stringify({ error: 'Failed to read or parse plugins.json' }));
                    return;
                  }
                } else {
                  res.statusCode = 404;
                  res.end(JSON.stringify({ error: 'plugins.json not found' }));
                  return;
                }
              }

              // Serve repository manifest xr3ed.json
              if (url === '/xr3ed.json' || url === '/build-out/xr3ed.json') {
                const repoData = {
                  name: "M3U Playlist Player Repo",
                  iconUrl: "https://play-lh.googleusercontent.com/V4t4JeQV2Cu9u72hKuqOW5c0IfwcZuuVS1d9PF2uJsW3rlIq-aOMTrT5bABVGaAFTcM=w480-h960-rw",
                  description: "M3U Playlist Player extension for Cloudstream (Dev Server)",
                  manifestVersion: 1,
                  pluginLists: [`${baseUrl}/plugins.json`]
                };
                res.setHeader('Content-Type', 'application/json; charset=utf-8');
                res.setHeader('Access-Control-Allow-Origin', '*');
                res.end(JSON.stringify(repoData, null, 2));
                return;
              }

              // Serve .cs3 compiled plugin binary files
              if (url.endsWith('.cs3')) {
                const pluginName = path.basename(url, '.cs3');
                const cs3Path = path.resolve(__dirname, `${pluginName}/build/${pluginName}.cs3`);
                if (fs.existsSync(cs3Path)) {
                  res.setHeader('Content-Type', 'application/octet-stream');
                  res.setHeader('Access-Control-Allow-Origin', '*');
                  res.setHeader('Content-Disposition', `attachment; filename="${pluginName}.cs3"`);
                  fs.createReadStream(cs3Path).pipe(res);
                  return;
                } else {
                  res.statusCode = 404;
                  res.end('Plugin binary .cs3 file not found');
                  return;
                }
              }

              next();
            });
          }
        }
      ],
      define: {
        'process.env.API_KEY': JSON.stringify(env.GEMINI_API_KEY),
        'process.env.GEMINI_API_KEY': JSON.stringify(env.GEMINI_API_KEY)
      },
      resolve: {
        alias: {
          '@': path.resolve(__dirname, '.'),
        }
      }
    };
});
