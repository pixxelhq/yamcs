import { ChangeDetectionStrategy, Component } from '@angular/core';
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
export class KeyinfoComponent {
    tmKeyId$: Promise<KeyInfo>;
    tcKeyId$: Promise<KeyInfo>
    payKeyId$: Promise<KeyInfo>

  constructor(
    readonly yamcs: YamcsService,
    title: Title,
    private sanitizer: DomSanitizer
  ) {
    title.setTitle('Key Management');

    const dlTmOptions: ActiveKeyRequest = {family: 'tm'};
    const dlTcOptions: ActiveKeyRequest = {family: 'tc'};
    const dlPayOptions: ActiveKeyRequest = {family: 'pay'};

    this.tmKeyId$ = yamcs.yamcsClient.getActiveKeyId(yamcs.instance!, dlTmOptions);
    this.tcKeyId$ = yamcs.yamcsClient.getActiveKeyId(yamcs.instance!, dlTcOptions);
    this.payKeyId$ = yamcs.yamcsClient.getActiveKeyId(yamcs.instance!, dlPayOptions);
  }
}
