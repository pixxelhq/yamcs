import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { Title, DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { KeyInfo, UpdateKeyRequest, ActiveKeyRequest, WebappSdkModule, YamcsService } from '@yamcs/webapp-sdk';
import { InstancePageTemplateComponent } from '../../shared/instance-page-template/instance-page-template.component';
import { InstanceToolbarComponent } from '../../shared/instance-toolbar/instance-toolbar.component';
import { FormBuilder, FormGroup, Validators } from '@angular/forms';

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
  tmKeyId: string | null = null;
  tcKeyId: string | null = null;

  form: FormGroup;
  fb: FormBuilder;

  constructor(
    readonly yamcs: YamcsService,
    title: Title,
    fb: FormBuilder,
    private cdr: ChangeDetectorRef,
    private sanitizer: DomSanitizer
  ) {
    title.setTitle('Key Management');
    this.fb = fb;

    // Setup forms
    this.form = this.fb.group({
      family: ['tm', Validators.required],
      value: [null,
        [Validators.required, Validators.min(1), Validators.max(25)],
      ],
    });
  }

  ngOnInit(): void {
    this.fetchKeyIds();
  }

  fetchKeyIds(): void {
    this.yamcs.yamcsClient
      .getActiveKeyId(this.yamcs.instance!, {family: 'tm'})
      .then(keyInfo => {
        this.tmKeyId = keyInfo.keyId;
        this.cdr.detectChanges(); // Trigger Angular's change detection
      });

    this.yamcs.yamcsClient
      .getActiveKeyId(this.yamcs.instance!, {family: 'tc'})
      .then(keyInfo => {
        this.tcKeyId = keyInfo.keyId;
        this.cdr.detectChanges(); // Trigger Angular's change detection
      });
  }

  updateKeyIdd(): void {
    if (this.form.valid) {
      this.yamcs.yamcsClient
        .updateKeyId(this.yamcs.instance!, {family: this.form.get('family')?.value, keyId: this.form.get('value')?.value.toString()})
        .then(resp => {
          this.fetchKeyIds();
        })
    }
  }
}
