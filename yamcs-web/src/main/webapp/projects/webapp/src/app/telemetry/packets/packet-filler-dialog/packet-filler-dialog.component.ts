import { Component, Inject, OnDestroy } from '@angular/core';
import { UntypedFormControl, UntypedFormGroup, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { RebuildParametersOptions, WebappSdkModule, YamcsService, utils } from '@yamcs/webapp-sdk';
import { BehaviorSubject, Subscription } from 'rxjs';

@Component({
    standalone: true,
    templateUrl: './packet-filler-dialog.component.html',
    imports: [
        WebappSdkModule,
    ],
})
export class PacketFillerDialogComponent implements OnDestroy {

    private formChangeSubscription: Subscription;

    start$ = new BehaviorSubject<string | null>(null);
    stop$ = new BehaviorSubject<string | null>(null);

    form = new UntypedFormGroup({
        start: new UntypedFormControl(null, [Validators.required]),
        stop: new UntypedFormControl(null, [Validators.required]),
    });

    constructor(
        private dialogRef: MatDialogRef<PacketFillerDialogComponent>,
        private yamcs: YamcsService,
        private snackBar: MatSnackBar,
        @Inject(MAT_DIALOG_DATA) readonly data: any,
    ) {
        this.form.setValue({
            start: data.start ? utils.toISOString(data.start) : null,
            stop: data.stop ? utils.toISOString(data.stop) : null,
        });
    }

    closeDialog() {
        this.dialogRef.close(true);
    }

    triggerRebuild() {
        const dlOptions: RebuildParametersOptions = {};
        if (this.form.value['start']) {
            dlOptions.start = utils.toISOString(this.form.value['start']);
        }
        if (this.form.value['stop']) {
            dlOptions.stop = utils.toISOString(this.form.value['stop']);
        }
        this.yamcs.yamcsClient.triggerParameterRebuild(
            this.yamcs.instance!, dlOptions
        ).then(() => {
            this.snackBar.open(`Rebuild Task of parameterArchive submitted. It will finish asynchronously`, undefined, {
                duration: 3000,
                horizontalPosition: 'end',
            });
        }).catch(err => {
            this.snackBar.open(`Rebuild of parameterArchive not successful`, undefined, {
                duration: 3000,
                horizontalPosition: 'end',
            });
        })

        this.closeDialog();
    }

    ngOnDestroy() {
        this.formChangeSubscription?.unsubscribe();
    }

    // Add to your component
    get formIsValid() {
        return this.form.valid;
    }
}
