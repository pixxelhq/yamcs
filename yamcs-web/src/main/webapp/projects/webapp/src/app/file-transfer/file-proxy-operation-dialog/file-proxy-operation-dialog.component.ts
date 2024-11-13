import { Component, Inject, OnDestroy, OnInit } from '@angular/core';
import { UntypedFormBuilder, UntypedFormGroup, Validators, UntypedFormArray, ReactiveFormsModule } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { FileTransferOption, FileProxyOperationOption, FileProxyOperationValue, FileTransferService, MessageService, PreferenceStore, WebappSdkModule, YamcsService } from '@yamcs/webapp-sdk';
import { CommonModule } from '@angular/common';

@Component({
  standalone: true,
  selector: 'app-file-proxy-operation-dialog',
  templateUrl: './file-proxy-operation-dialog.component.html',
  styleUrl: './file-proxy-operation-dialog.component.css',
  imports: [
    CommonModule,
    ReactiveFormsModule,
    WebappSdkModule,
  ],
})
export class FileProxyOperationDialogComponent implements OnInit, OnDestroy {
  form: UntypedFormGroup;
  formBuilder: UntypedFormBuilder;
  readonly service: FileTransferService;

  displayedColumns = ['name'];

  private prefPrefix = 'filetransferFP.';

  optionsMapping = new Map<FileTransferOption, string>();

  fpo: FileProxyOperationOption;
  fpoActions: { value: string; verboseName?: string | undefined }[] = [];  // Store dropdown options dynamically

  readonly DROPDOWN_SUFFIX = "_Dropdown";
  readonly CUSTOM_OPTION_VALUE = "_CUSTOM_OPTION_";

  constructor(
    private dialogRef: MatDialogRef<FileProxyOperationDialogComponent>,
    readonly yamcs: YamcsService,
    formBuilder: UntypedFormBuilder,
    private messageService: MessageService,
    private preferenceStore: PreferenceStore,
    private snackBar: MatSnackBar,
    @Inject(MAT_DIALOG_DATA) readonly data: any,
  ) {
    this.formBuilder = formBuilder;
    this.service = data.service;
    this.prefPrefix += this.service.name + ".";
    this.fpo = this.service.fileProxyOperationOption;

    const firstLocalEntity = this.service.localEntities && this.service.localEntities.length ? this.service.localEntities[0].name : '';
    const firstRemoteEntity = this.service.remoteEntities && this.service.remoteEntities.length ? this.service.remoteEntities[0].name : '';
    const localEntity$ = this.addPreference$('localEntity', firstLocalEntity);
    const remoteEntity$ = this.addPreference$('remoteEntity', firstRemoteEntity);

    // Prepare form control names for custom options
    let controlNames: { [key: string]: any; } = {};

    this.service.transferOptions?.forEach(
      (option, index) => {
        let name = this.getControlName(option, index);
        this.optionsMapping.set(option, name);

        if (option.type === "BOOLEAN") {
          const optionPref$ = this.addPreference$("options." + option.name, option.default?.toLowerCase() === "true");
          controlNames[name] = [optionPref$.value, []];
        } else {
          const optionPref$ = this.addPreference$("options." + option.name, option.default || '');
          let inValues = option.values && option.values.find(item => item.value === optionPref$.value);

          controlNames[name] = [inValues == null ? optionPref$.value : "", []];
          controlNames[name + this.DROPDOWN_SUFFIX] = [inValues != null ? optionPref$.value : this.CUSTOM_OPTION_VALUE, []];
        }
      }
    );

    // Setup forms
    this.form = formBuilder.group({
      fileProxyOps: this.formBuilder.array([]),
      localEntity: [localEntity$.value, this.service.localEntities && this.service.localEntities.length && Validators.required],
      remoteEntity: [remoteEntity$.value, this.service.remoteEntities && this.service.remoteEntities.length && Validators.required],
      ...controlNames
    });

    // Update entity user preference
    this.form.get('localEntity')?.valueChanges.subscribe((entity: any) => {
      this.setPreferenceValue("localEntity", entity);
    });

    // If a new destination is selected -> display cached file list if any
    this.form.get('remoteEntity')?.valueChanges.subscribe((entity: any) => {
      this.setPreferenceValue("remoteEntity", entity);
    });

    // Save option preferences
    this.form.valueChanges.subscribe(async _ => {
      this.optionsMapping.forEach((controlName, option) => {
        const dropDownValue = this.form.get(controlName + this.DROPDOWN_SUFFIX)?.value;
        let value = dropDownValue == null || dropDownValue === this.CUSTOM_OPTION_VALUE ? this.form.get(controlName)?.value : dropDownValue;

        switch (option.type) {
          case "BOOLEAN":
            this.setPreferenceValue("options." + option.name, String(value) === "true");
            break;
          case "DOUBLE":
          case "STRING":
            this.setPreferenceValue("options." + option.name, value != null ? value : "");
        }
      });

      // FIXME: Save FPO preferences
    });
  }

  // Add to your component
  get formIsValid() {
    return this.form.valid && this.fileProxyOps.length > 0;
  }

  ngOnInit() {
    // Initialize form immediately after component creation
    if (this.fpo.action && this.fpo.action.values) {
      this.fpoActions = this.fpo.action.values;
    }

    // Add initial field set with a slight delay to ensure proper initialization
    setTimeout(() => {
      this.addFieldSet();
    });
  }

  // Getter to access the FormArray named 'fieldSets' in the form group
  get fileProxyOps(): UntypedFormArray {
    return this.form.get('fileProxyOps') as UntypedFormArray;  // Ensure the FormArray is properly cast
  }

  addFieldSet() {
    const actionName = this.fpo.action.name;
    const firstFileName = this.fpo.firstFileName.name;
    const secondFileName = this.fpo.secondFileName.name;

    const fieldSet = this.formBuilder.group({
      [actionName] : ['', Validators.required],  // Dropdown field
      [firstFileName]: ['', Validators.required],  // First text field
      [secondFileName]: ['']   // Second text field
    });
    this.fileProxyOps.push(fieldSet);
  }

  // Method to remove a field set by index
  removeFieldSet(index: number) {
    this.fileProxyOps.removeAt(index);
  }

  getControlName(option: FileTransferOption, index: number) {
    return "option" + index + option.name.replace(/\s/g, "");
  }

  async startDownload() {
    return this.startTransfer(
      this.form.get("remoteEntity")?.value,
      this.form.get("localEntity")?.value,
      "DOWNLOAD"
    );
  }

  async startUpload() {
    if (!this.formIsValid) {
      return;
    }

    await this.startTransfer(
      this.form.get("localEntity")?.value,
      this.form.get("remoteEntity")?.value,
      "UPLOAD"
    );
  }

  async startTransfer(sourceEntity: string, destinationEntity: string, direction: "UPLOAD" | "DOWNLOAD") {
    let anyError: any;
    let errorCount = 0;

    try {
      // Direct API call without wrapping in a function
      await this.yamcs.yamcsClient.createFileTransfer(this.yamcs.instance!, this.service.name, {
        direction: direction,
        bucket: "",
        objectName: "",
        remotePath: "",
        source: sourceEntity,
        destination: destinationEntity,
        options: this.getTransferOptions(),
        fileProxyOperationOptions: this.getFpoOptions()
      });
    } catch (err) {
      anyError = err;
      errorCount++;
    }

    if (anyError) {
      if (errorCount === 1) {
        this.messageService.showError(anyError);
      } else {
        this.messageService.showError('Some of the transfers failed to start. See server log.');
      }
    }

    this.dialogRef.close();
  }

  private getFpoOptions() {
    const fpoValues: FileProxyOperationValue[]| undefined = this.form.get('fileProxyOps')?.value;

    if (fpoValues) {
      return fpoValues;
    }
    return [];
  }

  private getTransferOptions() {
    return this.service.transferOptions?.reduce((options, option) => {
      const controlName = this.optionsMapping.get(option);
      if (!controlName) {
        return options;
      }

      const dropDownValue = this.form.get(controlName + this.DROPDOWN_SUFFIX)?.value;
      let value = dropDownValue == null || dropDownValue === this.CUSTOM_OPTION_VALUE ? this.form.get(controlName)?.value : dropDownValue;

      if (option.type === "BOOLEAN" && typeof value !== "boolean") {
        value = String(value).toLowerCase() === "true";
      } else if (option.type === "DOUBLE" && typeof value !== "number") {
        value = Number(value);
      } else if (option.type === "STRING" && typeof value !== "string") {
        value = String(value);
      }

      return {
        ...options,
        [option.name]: value,
      };
    }, {});
  }

  private addPreference$<Type>(key: string, defaultValue: Type) {
    return this.preferenceStore.addPreference$(this.prefPrefix + key, defaultValue);
  }

  private setPreferenceValue<Type>(key: string, value: Type) {
    this.preferenceStore.setValue(this.prefPrefix + key, value);
  }

  ngOnDestroy() {
  }
}