import * as path from 'path';

/** True when two absolute paths name the same file; case does not matter on Windows. */
export function samePath(a: string, b: string, platform: NodeJS.Platform = process.platform): boolean {
    const x = path.normalize(a);
    const y = path.normalize(b);
    return platform === 'win32' ? x.toLowerCase() === y.toLowerCase() : x === y;
}
