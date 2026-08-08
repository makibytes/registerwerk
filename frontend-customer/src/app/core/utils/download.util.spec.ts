import { downloadBlob } from './download.util';

describe('downloadBlob', () => {
  beforeEach(() => jasmine.clock().install());
  afterEach(() => jasmine.clock().uninstall());

  it('clicks a temporary anchor and revokes its object URL after a grace period', () => {
    const createObjectUrl = spyOn(URL, 'createObjectURL').and.returnValue('blob:test');
    const revokeObjectUrl = spyOn(URL, 'revokeObjectURL');
    const click = spyOn(HTMLAnchorElement.prototype, 'click');

    downloadBlob(new Blob(['content']), ' report.pdf ');

    expect(createObjectUrl).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(document.querySelector('a[href="blob:test"]')).toBeNull();
    expect(revokeObjectUrl).not.toHaveBeenCalled();

    jasmine.clock().tick(10_000);
    expect(revokeObjectUrl).toHaveBeenCalledOnceWith('blob:test');
  });
});
