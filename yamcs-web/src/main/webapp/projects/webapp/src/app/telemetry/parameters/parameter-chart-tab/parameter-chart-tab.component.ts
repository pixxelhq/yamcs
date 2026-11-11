import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, QueryList, ViewChildren, input, signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { ActivatedRoute, Router } from '@angular/router';
import { BackfillingSubscription, ConfigService, Parameter, Synchronizer, WebappSdkModule, YamcsService, utils } from '@yamcs/webapp-sdk';
import { BehaviorSubject, Subscription } from 'rxjs';
import { DyDataSource } from '../../../shared/parameter-plot/DyDataSource';
import { CategoricalValue, getCategoricalValues } from '../../../shared/parameter-plot/categorical';
import { ParameterPlotComponent } from '../../../shared/parameter-plot/parameter-plot.component';
import { ParameterSeriesComponent } from '../../../shared/parameter-plot/parameter-series/parameter-series.component';
import { CompareParameterDialogComponent } from '../compare-parameter-dialog/compare-parameter-dialog.component';
import { SelectRangeDialogComponent } from '../select-range-dialog/select-range-dialog.component';

/**
 * 'overlay': all traces in one plot, sharing one Y-axis (the classic layout).
 * 'split': one plot per trace, stacked vertically, each with its own Y-axis,
 *          all following the same time window.
 */
export type ChartLayout = 'overlay' | 'split';

interface Trace {
  parameter: Parameter;
  color: string;
  strokeWidth: number;
}

/**
 * One plot of the chart, with its own data source. Views are recreated
 * whenever the layout or the set of traces changes.
 */
interface PlotView {
  id: number;
  dataSource: DyDataSource;
  traces: Trace[];
}

const DEFAULT_COLOR = '#1b61b9';

@Component({
  standalone: true,
  templateUrl: './parameter-chart-tab.component.html',
  styleUrl: './parameter-chart-tab.component.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ParameterPlotComponent,
    ParameterSeriesComponent,
    WebappSdkModule,
  ],
})
export class ParameterChartTabComponent implements OnInit, OnDestroy {

  qualifiedName = input.required<string>({ alias: 'parameter' });

  @ViewChildren(ParameterPlotComponent)
  plots: QueryList<ParameterPlotComponent>;

  parameter$: Promise<Parameter>;
  missionTime: Date;
  private timeSubscription: Subscription;
  private backfillSubscription: BackfillingSubscription;

  range$ = new BehaviorSubject<string>('PT15M');
  customStart$ = new BehaviorSubject<Date | null>(null);
  customStop$ = new BehaviorSubject<Date | null>(null);

  /**
   * Deliberately not persisted: every page load starts in overlay layout.
   */
  chartLayout = signal<ChartLayout>('overlay');

  views = signal<PlotView[]>([]);

  enumKeys = signal<{ name: string; color: string; values: CategoricalValue[]; }[]>([]);

  private traces: Trace[] = [];
  private nextViewId = 1;

  // Time window shared by all plots
  private windowStart: Date;
  private windowStop: Date;

  constructor(
    private router: Router,
    private route: ActivatedRoute,
    readonly yamcs: YamcsService,
    private dialog: MatDialog,
    private synchronizer: Synchronizer,
    private configService: ConfigService,
  ) { }

  ngOnInit() {
    this.missionTime = this.yamcs.getMissionTime();
    this.initializeOptions();

    const qualifiedName = this.qualifiedName();
    this.parameter$ = this.yamcs.yamcsClient.getParameter(this.yamcs.instance!, qualifiedName);
    this.parameter$.then(parameter => {
      // Override qualified name for possible array or aggregate offsets
      parameter.qualifiedName = qualifiedName;
      this.traces = [{ parameter, color: DEFAULT_COLOR, strokeWidth: 2 }];

      const interval = this.range$.value;
      if (interval === 'CUSTOM') {
        this.windowStart = this.customStart$.value!;
        this.windowStop = this.customStop$.value!;
      } else {
        this.windowStop = this.yamcs.getMissionTime();
        this.windowStart = utils.subtractDuration(this.windowStop, this.range$.value);
      }
      this.rebuildViews();

      // Autoscroll (don't care about data, that is triggered by plot buffer)
      this.timeSubscription = this.yamcs.time$.subscribe(() => {
        if (this.range$.value !== 'CUSTOM') {
          const stop = this.yamcs.getMissionTime();
          const start = utils.subtractDuration(stop, this.range$.value);
          this.windowStart = start;
          this.windowStop = stop;
          this.plots?.forEach(plot => plot.updateWindowOnly(start, stop));
        }
      });

      this.backfillSubscription = this.yamcs.yamcsClient.createBackfillingSubscription({
        instance: this.yamcs.instance!
      }, update => {
        if (update.finished) {
          for (const view of this.views()) {
            view.dataSource.reloadVisibleRange();
          }
        }
      });
    });
  }

  /**
   * Replaces all plots, according to the current layout and traces. Each plot
   * gets a fresh data source that loads the current time window.
   */
  private rebuildViews() {
    for (const view of this.views()) {
      view.dataSource.disconnect();
    }

    const groups = this.chartLayout() === 'split'
      ? this.traces.map(trace => [trace])
      : [this.traces];

    const views: PlotView[] = [];
    for (const traces of groups) {
      const dataSource = new DyDataSource(this.yamcs, this.synchronizer, this.configService);
      dataSource.addParameter(...traces.map(trace => trace.parameter));
      dataSource.updateWindow(this.windowStart, this.windowStop, [null, null]);
      views.push({ id: this.nextViewId++, dataSource, traces });
    }
    this.views.set(views);

    const enumKeys = [];
    for (const trace of this.traces) {
      const values = getCategoricalValues(trace.parameter.type);
      if (values) {
        enumKeys.push({ name: trace.parameter.name, color: trace.color, values });
      }
    }
    this.enumKeys.set(enumKeys);
  }

  setLayout(layout: ChartLayout) {
    if (this.chartLayout() !== layout) {
      this.chartLayout.set(layout);
      this.rebuildViews();
    }
  }

  private initializeOptions() {
    const queryParams = this.route.snapshot.queryParamMap;
    if (queryParams.has('interval')) {
      this.range$.next(queryParams.get('interval')!);
      if (queryParams.get('interval') === 'CUSTOM') {
        this.customStart$.next(new Date(queryParams.get('customStart')!));
        this.customStop$.next(new Date(queryParams.get('customStop')!));
      }
    }
    this.updateURL();
  }

  onVisibleRange(xRange: [Date, Date]) {
    this.customStart$.next(xRange[0]);
    this.customStop$.next(xRange[1]);
  }

  onManualRangeChange() {
    this.range$.next('CUSTOM');
  }

  /**
   * Makes the other plots follow a pan or zoom of one plot.
   */
  onXRangeChange(source: PlotView, xRange: [Date, Date]) {
    this.windowStart = xRange[0];
    this.windowStop = xRange[1];
    for (const view of this.views()) {
      if (view !== source) {
        view.dataSource.updateWindow(xRange[0], xRange[1], [null, null]);
      }
    }
  }

  onSeriesRemove(qualifiedName: string) {
    this.traces = this.traces.filter(trace => trace.parameter.qualifiedName !== qualifiedName);
    this.rebuildViews();
  }

  onSeriesModify(config: ParameterSeriesComponent) {
    const trace = this.traces.find(t => t.parameter.qualifiedName === config.parameter);
    if (trace) {
      trace.color = config.color;
      trace.strokeWidth = config.strokeWidth;
    }
  }

  loadLatest(range: string) {
    this.range$.next(range);
    const stop = this.yamcs.getMissionTime();
    const start = utils.subtractDuration(stop, range);
    this.updateURL();
    this.updateAllWindows(start, stop);
  }

  private updateAllWindows(start: Date, stop: Date) {
    this.windowStart = start;
    this.windowStop = stop;
    for (const view of this.views()) {
      view.dataSource.updateWindow(start, stop, [null, null]);
    }
  }

  zoomIn() {
    // Other plots follow through xRangeChange
    this.plots.first?.zoomIn();
  }

  zoomOut() {
    this.plots.first?.zoomOut();
  }

  chooseRange() {
    const currentRange = this.plots.first?.getDateRange();
    if (currentRange) {
      const dialogRef = this.dialog.open(SelectRangeDialogComponent, {
        width: '400px',
        data: {
          start: currentRange[0],
          stop: currentRange[1],
        },
      });

      dialogRef.afterClosed().subscribe(result => {
        if (result) {
          this.range$.next('CUSTOM');
          this.customStart$.next(result.start);
          this.customStop$.next(result.stop);
          this.updateURL();
          this.updateAllWindows(result.start, result.stop);
        }
      });
    }
  }

  compareParameter() {
    const dialogRef = this.dialog.open(CompareParameterDialogComponent, {
      width: '600px',
      data: {
        exclude: this.traces.map(trace => trace.parameter),
      }
    });
    dialogRef.afterClosed().subscribe(result => {
      if (result) {
        this.yamcs.yamcsClient.getParameter(this.yamcs.instance!, result.qualifiedName).then(parameter => {
          // Override qualified name for possible array or aggregate offsets
          parameter.qualifiedName = result.qualifiedName;
          this.traces = [...this.traces, {
            parameter,
            color: result.color,
            strokeWidth: result.thickness,
          }];
          this.rebuildViews();
        });
      }
    });
  }

  private updateURL() {
    this.router.navigate([], {
      replaceUrl: true,
      relativeTo: this.route,
      queryParams: {
        interval: this.range$.value,
        customStart: this.range$.value === 'CUSTOM' ? this.customStart$.value : null,
        customStop: this.range$.value === 'CUSTOM' ? this.customStop$.value : null,
      },
      queryParamsHandling: 'merge',
    });
  }

  ngOnDestroy() {
    this.backfillSubscription?.cancel();
    this.timeSubscription?.unsubscribe();
    for (const view of this.views()) {
      view.dataSource.disconnect();
    }
  }
}
