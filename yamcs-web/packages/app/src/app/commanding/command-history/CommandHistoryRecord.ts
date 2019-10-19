import { CommandAssignment, CommandHistoryEntry } from '@yamcs/client';
import * as utils from '../../shared/utils';
import { Acknowledgment } from './Acknowledgment';

export class CommandHistoryRecord {

  generationTime: string;
  origin: string;
  sequenceNumber: number;

  commandName: string;

  assignments: CommandAssignment[] = [];
  userAssignments: CommandAssignment[] = [];

  username: string;

  source: string;
  binary: string;

  comment?: string;

  queued?: Acknowledgment;
  released?: Acknowledgment;
  sent?: Acknowledgment;
  extraAcks: Acknowledgment[] = [];
  verifications: Acknowledgment[] = [];

  extra: { [key: string]: string }[] = [];

  completed = false;
  success?: boolean;
  failureMessage?: string;

  private acksByName: { [key: string]: Acknowledgment } = {};

  constructor(entry: CommandHistoryEntry) {
    this.generationTime = entry.generationTimeUTC;
    this.origin = entry.commandId.origin;
    this.sequenceNumber = entry.commandId.sequenceNumber;
    this.commandName = entry.commandId.commandName;

    for (const assignment of entry.assignment) {
      this.assignments.push(assignment);
      if (assignment.userInput) {
        this.userAssignments.push(assignment);
      }
    }

    for (const attr of entry.attr) {
      if (attr.name === 'username') {
        this.username = attr.value.stringValue!;
      } else if (attr.name === 'source') {
        this.source = attr.value.stringValue!;
      } else if (attr.name === 'binary') {
        this.binary = attr.value.binaryValue!;
      } else if (attr.name === 'CommandFailed') {
        this.failureMessage = attr.value.stringValue!;
      } else if (attr.name === 'CommandComplete') {
        this.completed = true;
        this.success = attr.value.stringValue === 'OK';
      } else if (attr.name === 'Comment' || attr.name === 'comment') { // Old versions of Yamcs use "Comment" with capital
        this.comment = attr.value.stringValue;
      } else if (attr.name.endsWith('_Time')) {
        const ackName = attr.name.substring(0, attr.name.length - '_Time'.length);
        this.saveAckTime(ackName, attr.value.stringValue!);
      } else if (attr.name.endsWith('_Status')) {
        const ackName = attr.name.substring(0, attr.name.length - '_Status'.length);
        this.saveAckStatus(ackName, attr.value.stringValue!);
      } else {
        this.extra.push({ name: attr.name, value: utils.printValue(attr.value) });
      }
    }

    for (const ack of Object.values(this.acksByName)) {
      if (ack.name === 'Acknowledge_Queued') {
        this.queued = ack;
      } else if (ack.name === 'Acknowledge_Released') {
        this.released = ack;
      } else if (ack.name === 'Acknowledge_Sent') {
        this.sent = ack;
      } else if (ack.name!.indexOf('Verifier_') === 0) {
        this.verifications.push(ack);
      } else {
        this.extraAcks.push(ack);
      }
    }
  }

  private saveAckTime(name: string, time: string) {
    let ack: Acknowledgment | null = null;
    for (const key in this.acksByName) {
      if (key === name) {
        ack = this.acksByName[key];
        break;
      }
    }
    if (!ack) {
      ack = { name };
      this.acksByName[name] = ack;
    }
    ack.time = time;
  }

  private saveAckStatus(name: string, status: string) {
    let ack: Acknowledgment | null = null;
    for (const key in this.acksByName) {
      if (key === name) {
        ack = this.acksByName[key];
        break;
      }
    }
    if (!ack) {
      ack = { name };
      this.acksByName[name] = ack;
    }
    ack.status = status;
  }
}
