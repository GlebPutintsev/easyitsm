import QRCode from 'qrcode';

/**
 * Этикетка актива (10.14 «Напечатать QR»): QR со ссылкой на карточку, инвентарный номер и название.
 * Печатается из скрытого iframe, текст вставляется через DOM, а не строкой HTML.
 */
export async function printAssetLabel(asset: { id: string; tag: string; name: string }): Promise<void> {
  const url = `${window.location.origin}/agent/assets/${asset.id}`;
  const qr = await QRCode.toDataURL(url, { margin: 1, width: 240, errorCorrectionLevel: 'M' });
  const frame = document.createElement('iframe');
  frame.setAttribute('aria-hidden', 'true');
  frame.style.position = 'fixed';
  frame.style.width = '0';
  frame.style.height = '0';
  frame.style.border = '0';
  document.body.appendChild(frame);
  const doc = frame.contentDocument;
  const win = frame.contentWindow;
  if (!doc || !win) {
    frame.remove();
    return;
  }
  doc.title = asset.tag;
  const style = doc.createElement('style');
  style.textContent =
    '@page{size:58mm 40mm;margin:2mm}body{margin:0;font-family:sans-serif;display:flex;gap:3mm;align-items:center}' +
    'img{width:30mm;height:30mm}.tag{font:700 12pt monospace}.name{font-size:8pt;margin-top:1mm}';
  doc.head.appendChild(style);
  const img = doc.createElement('img');
  img.alt = asset.tag;
  img.src = qr;
  const text = doc.createElement('div');
  const tag = doc.createElement('div');
  tag.className = 'tag';
  tag.textContent = asset.tag;
  const name = doc.createElement('div');
  name.className = 'name';
  name.textContent = asset.name;
  text.append(tag, name);
  doc.body.append(img, text);
  await img.decode().catch(() => undefined);
  win.focus();
  win.print();
  setTimeout(() => frame.remove(), 1000);
}
