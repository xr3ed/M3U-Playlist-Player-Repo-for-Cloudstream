const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const buildOutDir = path.join(__dirname, '../build-out');
const prebuiltsDir = __dirname;

const pluginsPath = path.join(buildOutDir, 'plugins.json');
const prebuiltsPath = path.join(prebuiltsDir, 'prebuilts.json');

if (!fs.existsSync(pluginsPath)) {
  console.error('Error: build-out/plugins.json not found!');
  process.exit(1);
}

if (!fs.existsSync(prebuiltsPath)) {
  console.error('Error: prebuilts/prebuilts.json not found!');
  process.exit(1);
}

// 1. Read files
let plugins = [];
try {
  plugins = JSON.parse(fs.readFileSync(pluginsPath, 'utf8'));
} catch (e) {
  console.error('Failed to parse build-out/plugins.json', e);
  process.exit(1);
}

let prebuilts = [];
try {
  prebuilts = JSON.parse(fs.readFileSync(prebuiltsPath, 'utf8'));
} catch (e) {
  console.error('Failed to parse prebuilts/prebuilts.json', e);
  process.exit(1);
}

// 2. Merge without duplication by name
const pluginMap = new Map();
plugins.forEach(p => pluginMap.set(p.name, p));

prebuilts.forEach(p => {
  // Also copy the .cs3 file to build-out
  const srcCs3 = path.join(prebuiltsDir, `${p.internalName || p.name}.cs3`);
  const destCs3 = path.join(buildOutDir, `${p.internalName || p.name}.cs3`);
  
  if (fs.existsSync(srcCs3)) {
    fs.copyFileSync(srcCs3, destCs3);
    console.log(`Successfully copied prebuilt ${p.name}.cs3 to build-out`);
    
    // Automatically calculate size and hash from the actual file
    try {
      const stats = fs.statSync(srcCs3);
      const fileBuffer = fs.readFileSync(srcCs3);
      const hashSum = crypto.createHash('sha256');
      hashSum.update(fileBuffer);
      const hex = hashSum.digest('hex');
      
      p.fileSize = stats.size;
      p.fileHash = `sha256-${hex}`;
      console.log(`Automatically updated metadata for prebuilt ${p.name}: size=${p.fileSize}, hash=${p.fileHash}`);
    } catch (e) {
      console.error(`Failed to calculate size and hash for ${srcCs3}`, e);
    }
  } else {
    console.warn(`Warning: prebuilt file ${srcCs3} not found!`);
  }
  
  pluginMap.set(p.name, p);
});

const mergedPlugins = Array.from(pluginMap.values());

// 3. Save back
fs.writeFileSync(pluginsPath, JSON.stringify(mergedPlugins, null, 2), 'utf8');
console.log('Successfully merged prebuilts into build-out/plugins.json');

