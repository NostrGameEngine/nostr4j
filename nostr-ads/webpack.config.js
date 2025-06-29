const path = require('path');
const fs = require('fs');
const webpack = require('webpack');

// Function to recursively scan directories and create a virtual file system
function createVirtualFileSystem(dir, baseDir = dir, withTaxonomyCSV = false) {
    const virtualFS = {};
    try {
        const items = fs.readdirSync(dir);

        items.forEach(item => {
           
            const fullPath = path.join(dir, item);
            const relativePath = path.relative(baseDir, fullPath);

            if (fs.statSync(fullPath).isDirectory()) {
                Object.assign(virtualFS, createVirtualFileSystem(fullPath, baseDir));
            } else {
                if (!item
                    || item.endsWith('.js')
                    || item.endsWith('.js.map')
                    || item.endsWith('.java')
                    || item.endsWith('.ts')
                    || item.endsWith('.d.ts')
                    || item.endsWith('.js')
                    || item.endsWith('.js.map')
                ) return;
                
                if (!withTaxonomyCSV && item.endsWith('nostr-content-taxonomy.csv')) return;
                
                // Encode file content as base64
                const fileBuffer = fs.readFileSync(fullPath);
                virtualFS[relativePath] = fileBuffer.toString('base64');
            
            }
        });
    } catch (err) {
        console.warn('Error reading directory:', err);
    }

    return virtualFS;
}

// Create the virtual file system from TeaVM output
const teavmDir = path.resolve(__dirname, 'build/generated/teavm/js');
const virtualFS = createVirtualFileSystem(teavmDir);

// Create a wrapper entry point that re-exports everything


const virtualFSJSON = JSON.stringify(virtualFS);

module.exports = {
    mode: 'production',
    entry: path.resolve(__dirname, "build/generated/teavm/js/NostrAds.js")    ,
    devtool: 'source-map',
    output: {
        path: path.resolve(__dirname, 'build/generated/webpack'),
        filename: 'nostr-ads.js',
        library: {
            name: 'NostrAds',
            type: 'umd',
            export: 'default',
        },
        globalObject: 'this',
        sourceMapFilename: 'nostr-ads.js.map'
    },
    module: {
        rules: [
            {
                test: /\.js$/,
                exclude: /node_modules/,
                use: {
                    loader: 'babel-loader',
                    options: {
                        presets: [
                            ['@babel/preset-env', {
                                targets: '> 0.25%, not dead',
                                modules: false
                            }]
                        ]
                    }
                }
            }
        ]
    },
    resolve: {
        extensions: ['.js'],
        fallback: {
            crypto: false,
            buffer: require.resolve('buffer/'),
            path: require.resolve('path-browserify')
        }
    },
    plugins: [
        new webpack.ProvidePlugin({
            Buffer: ['buffer', 'Buffer'],
        }),
        new webpack.BannerPlugin({
            banner: `
(function() {
    if (typeof window !== 'undefined') {
        // Initialize NGEBundledResources if it doesn't exist
        window.NGEBundledResources = window.NGEBundledResources || {};
        
        // Add base64 encoded resources
        const resources = ${virtualFSJSON};
        Object.keys(resources).forEach(function(key) {
            window.NGEBundledResources[key] = resources[key];
        });
        
        // Ensure all exports are directly accessible on window.NostrAds
        Object.keys(this.NostrAds).forEach(function(key) {
            window.NostrAds[key] = this.NostrAds[key];
        }, this);
    }
})();
            `,
            raw: true,
            entryOnly: true,
            footer: true
        })
    ],
    optimization: {
        minimize: true
    }
};