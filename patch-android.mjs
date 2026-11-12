// Adds PrintPal's native code and "Share to PrintPal" support to the generated Android project.
import { readFileSync, writeFileSync, copyFileSync, mkdirSync } from 'node:fs';

const pkgDir = 'android/app/src/main/java/com/vinay/printpal';
mkdirSync(pkgDir, { recursive: true });
copyFileSync('native/MainActivity.java', `${pkgDir}/MainActivity.java`);
copyFileSync('native/PrinterPlugin.java', `${pkgDir}/PrinterPlugin.java`);

const manifestPath = 'android/app/src/main/AndroidManifest.xml';
let m = readFileSync(manifestPath, 'utf8');

const sendTypes = [
  'image/*', 'application/pdf', 'text/plain', 'text/html', 'text/csv',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
];
const data = (types) => types.map((t) => `                <data android:mimeType="${t}" />`).join('\n');
const filters = `
            <intent-filter android:label="Print with PrintPal">
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
${data(sendTypes)}
            </intent-filter>
            <intent-filter android:label="Print with PrintPal">
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
${data(['image/*', 'application/pdf'])}
            </intent-filter>
            <intent-filter android:label="Print with PrintPal">
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="content" />
                <data android:scheme="file" />
${data(['application/pdf', 'image/*'])}
            </intent-filter>
`;

if (!m.includes('android.intent.action.SEND')) {
  // Insert after the launcher intent-filter of MainActivity
  m = m.replace(/(<category android:name="android.intent.category.LAUNCHER" \/>\s*<\/intent-filter>)/, `$1\n${filters}`);
}
if (!m.includes('android.printservice.PrintService')) {
  // Lets the app list installed print services (Android 11+ package visibility)
  m = m.replace('</manifest>', `    <queries>
        <intent>
            <action android:name="android.printservice.PrintService" />
        </intent>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="market" />
        </intent>
    </queries>
</manifest>`);
}
writeFileSync(manifestPath, m);

if (!m.includes('android.intent.action.SEND')) throw new Error('Could not add share filters to the manifest');
console.log('Android project patched');
