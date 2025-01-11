import { AsyncPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { YaButton } from '../button/button.component';

@Component({
  standalone: true,
  selector: 'ya-detail-pane',
  templateUrl: './detail-pane.component.html',
  styleUrls: ['./detail-pane.component.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    CommonModule,
    AsyncPipe,
    YaButton
  ],
})
export class YaDetailPane {
  isCollapsed = false; // Track whether the detail panel is collapsed
}