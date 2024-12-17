import { ChangeDetectionStrategy, Component, OnInit } from '@angular/core';
import { Title, DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { KeyInfo, UpdateKeyRequest, ActiveKeyRequest, WebappSdkModule, YamcsService } from '@yamcs/webapp-sdk';
import { InstancePageTemplateComponent } from '../../shared/instance-page-template/instance-page-template.component';
import { InstanceToolbarComponent } from '../../shared/instance-toolbar/instance-toolbar.component';

@Component({
  standalone: true,
  templateUrl: './keyinfo.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    InstanceToolbarComponent,
    InstancePageTemplateComponent,
    WebappSdkModule,
  ],
})
export class KeyinfoComponent implements OnInit {
  currentKeyIds: { [family: string]: string } = {};
  selectedKeyIds: { [key: string]: string } = {};

  sections = [
    { title: 'Telemetry (TM)', family: 'tm' },
    { title: 'Telecommand (TC)', family: 'tc' },
    { title: 'Payload (PAY)', family: 'pay' }
  ];

  constructor(
    readonly yamcs: YamcsService,
    title: Title,
    private sanitizer: DomSanitizer
  ) {
    title.setTitle('Key Management');
  }

  ngOnInit(): void {
    this.sections.forEach((section) => {
      this.fetchCurrentKeyId(section);
    });
  }

  fetchCurrentKeyId(section: any) {
    this.yamcs.yamcsClient
        .getActiveKeyId(this.yamcs.instance!, {family: section.family})
        .then(keyInfo => {
          this.currentKeyIds[section.family] = keyInfo.keyId;
        });
  }

  submitKeyId(section: any) {
    const ulOptions: UpdateKeyRequest = {
      family: section.family,
      keyId: this.selectedKeyIds[section.family]
    }
    console.log(ulOptions);
    this.yamcs.yamcsClient.updateKeyId(this.yamcs.instance!, ulOptions)
        .then(() => {
            window.location.reload();
          }
        ).catch(err => {
            console.log('Failed to update keyID for family: ' + section.family, err);
          }
        );
  }

  onKeyIdChange(event: Event, section: any): void {
    const target = event.target as HTMLSelectElement;
    const selectedValue = target.value;
    this.submitKeyId(section);
  }
}
