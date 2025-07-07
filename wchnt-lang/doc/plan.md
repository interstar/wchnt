# WCHNT Language Development Plan

This document outlines the future development goals and features for the WCHNT language. The plan is organized by priority and complexity, with immediate goals first and more advanced features later.


### 1) Fixing construction compilation and unit testing, to make sure it works

### 2) we are going to rethink the structure of a WCHNT / assemblage source file.

In particular, we are going for a "literate programming" approach where we will embed code within markdown.

A WCHNT program will look like a markdown file with human readable text.

We will still use the convention of a level two heading (ie. a heading with two hash symbols in front of it) to separate the phases of our program

We will put the actual meaningful part of the code in each case within the triple backtack "fence" 

eg. a document will say something like ## Schema then the backtick fence, then the schema code, then close the backticks.

We can have other text in this section too, which is effectively comments.

Then there's the hash-hash Construction header. With more ordinary text and the code in the backtick fences.

Does this make sense.

We're going to add further phases or sections to our code, so for the moment, the definitive 5 sections of a WCHNT program will be

- 1) Schema
- 2) Construction
- 3) Reactive
- 4) Imperative
- 5) Target

Don't worry what goes into the last 3 yet. But we will now define the document to accept all 5 of these sections.

So ... the first part of the new parsing will be to take this markdown doc (still with a .wcn extension I think.) and extract the 5 code sections from it.

Then we will pass each code section to the approprate parser in our pipeline. 

This changes the initial parsing strategy somewhat. 
 
Note that of all the sections, only the first, the Schema section is required. A file that just contains a Schema will produce a (eg. Haxe) target file that just defines classes. Schema plus Construction is classes plus the factory file. Reactive and Imperative will add methods to the classes. And Target is for extra information.

### 3) context-specific classes

We haven't yet dealt with the context-specific classes in our construction phase. What this will involve is adding an argument to the constructor of each which takes a "parent".

However, because of issues of circularity, we will actually pass a future/promise type object which will get assigned to the context


## Phase 3: Reactive Features

### 3.1 Observable Properties
- **Basic Observables**: Properties that notify on change
  ```wchnt
  Person = String/name@observable int/age@observable
  ```
- **Computed Properties**: Derived values that update automatically
  ```wchnt
  Person = String/firstName@observable String/lastName@observable String/fullName@computed
  ```

### 3.2 Event Streams
- **Property Change Events**: Automatic event generation
  ```wchnt
  Person = String/name@observable EventStream<String>/onNameChange
  ```
- **Custom Events**: User-defined event streams
  ```wchnt
  Button = String/text EventStream<MouseEvent>/onClick@event
  ```

### 3.3 Reactive Bindings
- **One-way Bindings**: Automatic property synchronization
  ```wchnt
  Person = String/name@observable String/displayName@bind
  ```
- **Two-way Bindings**: Bidirectional synchronization
  ```wchnt
  Form = String/firstName@observable String/lastName@observable String/fullName@bind2way
  ```

## Phase 4: Validation and Constraints

### 4.1 Property Validation
- **Built-in Validators**: Common validation patterns
  ```wchnt
  Person = String/name@validate(notEmpty) int/age@validate(range(0,150))
  ```
- **Custom Validators**: User-defined validation functions
  ```wchnt
  Person = String/email@validate(emailFormat) String/phone@validate(phoneFormat)
  ```

### 4.2 Relationship Constraints
- **Uniqueness**: Ensure unique references
  ```wchnt
  Person = String/name ->Person/spouse@unique
  ```
- **Cascading**: Automatic cleanup of related objects
  ```wchnt
  Person = String/name [->Person]/children@cascade(delete)
  ```

