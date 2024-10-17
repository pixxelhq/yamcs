import { ChangeDetectionStrategy, Component } from '@angular/core';
import { Title, DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { MissionDatabase, MissionDatabaseVersion, MissionDatabaseHistory, MissionDatabaseHistoryRequest, WebappSdkModule, YamcsService } from '@yamcs/webapp-sdk';
import { InstancePageTemplateComponent } from '../../shared/instance-page-template/instance-page-template.component';
import { InstanceToolbarComponent } from '../../shared/instance-toolbar/instance-toolbar.component';

@Component({
  standalone: true,
  templateUrl: './overview-component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    InstanceToolbarComponent,
    InstancePageTemplateComponent,
    WebappSdkModule,
  ],
})
export class OverviewComponent {

  mdb$: Promise<MissionDatabase>;
  mdbV$: Promise<MissionDatabaseVersion>
  mdbH$: Promise<MissionDatabaseHistory>

  constructor(
    readonly yamcs: YamcsService,
    title: Title,
    private sanitizer: DomSanitizer
  ) {
    const numEntries = 35;
    const dlOptions: MissionDatabaseHistoryRequest = {numEntries: numEntries};
    title.setTitle('Mission database');
    this.mdb$ = yamcs.yamcsClient.getMissionDatabase(yamcs.instance!);
    this.mdbV$ = yamcs.yamcsClient.getMissionDatabaseVersion(yamcs.instance!);
    this.mdbH$ = yamcs.yamcsClient.getMissionDatabaseHistory(yamcs.instance!, dlOptions);
  }

  getMessageUrl(message: string): string {
    const urlIndex = message.indexOf('http');
    return message.substring(urlIndex);
  }

  // Method to replace '\n' with '<br>' and preserve indentations
  formatMessage(message: string): SafeHtml {
    const formattedMessage = message
      .replace(/\n/g, '<br>')                    // Replace '\n' with '<br>'
      .replace(/ {2,}/g, (spaces) => {           // Replace 2 or more spaces with &nbsp;
        return spaces.split('').map(() => '&nbsp;').join('');
      });
    return this.sanitizer.bypassSecurityTrustHtml(formattedMessage);
  }
}
