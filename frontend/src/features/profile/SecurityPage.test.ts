import { describe, expect, it } from 'vitest';
import { browserName } from './SecurityPage';

describe('browserName', () => {
  it('показывает браузер и систему по User-Agent', () => {
    expect(browserName('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36')).toBe('Chrome, Windows');
    expect(browserName('Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15')).toBe('Safari, macOS');
    expect(browserName('Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0')).toBe('Firefox, Linux');
    expect(browserName(null)).toBe('—');
  });
});
