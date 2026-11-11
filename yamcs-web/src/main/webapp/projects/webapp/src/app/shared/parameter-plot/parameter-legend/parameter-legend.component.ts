
import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { MatIcon } from '@angular/material/icon';
import { DyLegendData } from '../dygraphs';

@Component({
  standalone: true,
  selector: 'app-parameter-legend',
  templateUrl: './parameter-legend.component.html',
  styleUrl: './parameter-legend.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatIcon
],
})
export class ParameterLegendComponent {

  @Input()
  data: DyLegendData;

  @Input()
  backgroundColor: string;

  @Input()
  borderColor: string;

  @Input()
  closable = false;

  /**
   * Label of the series that cannot be closed. When unset, the first series
   * cannot be closed.
   */
  @Input()
  lockedLabel?: string;

  isClosable(label: string, index: number) {
    if (!this.closable) {
      return false;
    }
    return this.lockedLabel !== undefined ? label !== this.lockedLabel : index !== 0;
  }

  @Output()
  select = new EventEmitter<string>();

  @Output()
  close = new EventEmitter<string>();
}
