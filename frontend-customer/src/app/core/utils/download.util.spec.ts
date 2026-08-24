import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { downloadBlob } from './download.util';

describe('downloadBlob', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it('clicks a temporary anchor and revokes its object URL after a grace period', () => {
        const createObjectUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:test');
        const revokeObjectUrl = vi.spyOn(URL, 'revokeObjectURL').mockReturnValue(undefined);
        const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockReturnValue(undefined);

        downloadBlob(new Blob(['content']), ' report.pdf ');

        expect(createObjectUrl).toHaveBeenCalled();
        expect(click).toHaveBeenCalled();
        expect(document.querySelector('a[href="blob:test"]')).toBeNull();
        expect(revokeObjectUrl).not.toHaveBeenCalled();

        vi.advanceTimersByTime(10000);
        expect(revokeObjectUrl).toHaveBeenCalledTimes(1);
        expect(revokeObjectUrl).toHaveBeenCalledWith('blob:test');
    });
});
