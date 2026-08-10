import {
  booleanAttribute,
  ChangeDetectionStrategy,
  Component,
  input,
} from '@angular/core';
import { MatTooltip } from '@angular/material/tooltip';
import { BaseComponent } from '../../abc/BaseComponent';
import { YaIconAction } from '../icon-action/icon-action.component';

@Component({
  selector: 'ya-detail-toolbar',
  templateUrl: './detail-toolbar.component.html',
  styleUrl: './detail-toolbar.component.css',
  host: {
    class: 'ya-detail-toolbar',
  },
  changeDetection: ChangeDetectionStrategy.Eager,
  imports: [MatTooltip, YaIconAction],
})
export class YaDetailToolbar extends BaseComponent {
  alwaysOpen = input(false, { transform: booleanAttribute });
  closeIcon = input('close');
  closeLabel = input('Close pane');
}
