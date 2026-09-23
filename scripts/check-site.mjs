import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = path.resolve(process.argv[2] || "_site");
const baseUrl = (process.argv[3] || "").replace(/\/$/, "");
const failures = [];
let checked = 0;

function walk(dir) {
    return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const target = path.join(dir, entry.name);
        return entry.isDirectory() ? walk(target) : [target];
    });
}

function resolveTarget(htmlFile, rawUrl) {
    const clean = rawUrl.replace(/&amp;/g, "&").split(/[?#]/, 1)[0];
    if (!clean || clean.startsWith("#")) return null;
    if (/^(?:[a-z][a-z0-9+.-]*:|\/\/)/i.test(clean)) return null;

    let target;
    if (clean.startsWith("/")) {
        if (baseUrl && clean !== baseUrl && !clean.startsWith(baseUrl + "/")) {
            failures.push(`${path.relative(root, htmlFile)}: path escapes base URL: ${rawUrl}`);
            return null;
        }
        const relative = baseUrl ? clean.slice(baseUrl.length) : clean;
        target = path.join(root, relative.replace(/^\//, ""));
    } else {
        target = path.resolve(path.dirname(htmlFile), clean);
    }

    if (clean.endsWith("/")) target = path.join(target, "index.html");
    if (fs.existsSync(target) && fs.statSync(target).isDirectory()) target = path.join(target, "index.html");
    return target;
}

if (!fs.existsSync(root) || !fs.statSync(root).isDirectory()) {
    console.error(`Site output not found: ${root}`);
    process.exit(2);
}

for (const sourceFile of walk(root).filter((file) => /\.(?:html|js)$/.test(file))) {
    const source = fs.readFileSync(sourceFile, "utf8");
    const urls = [];
    if (sourceFile.endsWith(".html")) {
        urls.push(...Array.from(source.matchAll(/\b(?:href|src)\s*=\s*["']([^"']+)["']/gi), (match) => match[1]));
    }
    urls.push(
        ...Array.from(
            source.matchAll(/^\s*(?:import|export)\b[^\r\n]*?\bfrom\s*["']([^"']+)["']/gm),
            (match) => match[1],
        ),
        ...Array.from(source.matchAll(/^\s*import\s*["']([^"']+)["']/gm), (match) => match[1]),
    );

    for (const url of urls) {
        const target = resolveTarget(sourceFile, url);
        if (!target) continue;
        checked += 1;
        if (!fs.existsSync(target) || !fs.statSync(target).isFile()) {
            failures.push(`${path.relative(root, sourceFile)}: missing ${url}`);
        }
    }
}

if (failures.length) {
    console.error(`Site check failed with ${failures.length} broken internal reference(s):`);
    failures.forEach((failure) => console.error(`- ${failure}`));
    process.exit(1);
}

console.log(`Site check passed: ${checked} internal links/assets resolved.`);
